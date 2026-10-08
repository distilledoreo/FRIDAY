package com.localfirst.assistant.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.desktop.desktopRequest
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.settings.ServerSettings
import com.localfirst.assistant.tools.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

/** Uses the existing authenticated computer endpoint; no connected-service accounts. */
class WorkspaceClient(private val context: Context, private val settings: () -> ServerSettings) : WorkspaceGateway {
    var memoryScope: String = ""
    var incognito: Boolean = false
    var freshSlate: Boolean = false
    private var ephemeralId: String? = null
    fun useEphemeralSession(id: String?) { ephemeralId = id }
    private val ephemeralLock = kotlinx.coroutines.sync.Mutex()
    suspend fun ephemeralSession(): String = withContext(Dispatchers.IO) {
        ephemeralLock.lock()
        try {
            ephemeralId?.let { return@withContext it }
            val s = settings()
            val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "POST", "/workspace/incognito", "{}".toByteArray(), "application/json", timeoutMs = 15000)
            require(code == 200) { "The computer does not support incognito files yet (HTTP $code)." }
            return@withContext JSONObject(bytes.toString(Charsets.UTF_8)).getString("id").also { ephemeralId = it }
        } finally { ephemeralLock.unlock() }
    }
    private suspend fun privatePath(path: String): String {
        if (!incognito) return path
        if (path.startsWith("/workspace/images") && path != "/workspace/images/health" || path.startsWith("/workspace/files") || path == "/workspace/create-file") {
            return "/workspace/incognito/${ephemeralSession()}" + path.removePrefix("/workspace")
        }
        if (path.startsWith("/workspace/") && !(path == "/workspace/foreground" || path == "/workspace/memory/context" || path.startsWith("/workspace/memory/tool-search?kind=memory") && !freshSlate || path == "/workspace/images/health")) {
            error("This workspace action is disabled in incognito; it would save data.")
        }
        return path
    }
    suspend fun endIncognito() = withContext(Dispatchers.IO) {
        val id = ephemeralId
        ephemeralId = null; incognito = false; freshSlate = false
        try {
            if (id != null) {
                val s = settings()
                val (code, _) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "DELETE", "/workspace/incognito/$id", timeoutMs = 120000)
                require(code in 200..299 || code == 410) { "Incognito cleanup will retry after expiry on the computer." }
            }
        } finally { File(context.cacheDir, "incognito").deleteRecursively() }
    }
    val imageSettings = ImageSettingsStore(context)
    val imageStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override suspend fun toolRequest(path: String, method: String, body: String?): String {
        val payload = body?.let(::JSONObject)
        if (path == "/workspace/images" && method == "POST" && payload != null) imageSettings.apply(payload, serverIdentity)
        val target = if (path.startsWith("/workspace/memory/tool-search?")) path + "&scope=" + java.net.URLEncoder.encode(memoryScope, "UTF-8") else path
        val result = request(target, method, payload)
        if (path.startsWith("/workspace/images/") && method == "GET") {
            val job = JSONObject(result)
            imageStatus.value = if (job.optString("status") in listOf("completed", "failed", "cancelled")) null else job.optString("phase").replace('_', ' ')
        }
        if (path.startsWith("/workspace/images/") && path.endsWith("/cancel")) imageStatus.value = null
        return result
    }
    val serverIdentity: String get() = settings().searchBaseUrl.trim().trimEnd('/')
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, timeoutMs: Int = 120000): String = withContext(Dispatchers.IO) {
        val s = settings()
        if (path.startsWith("/workspace/agent/accounts")) {
            val endpoint = java.net.URI(s.searchBaseUrl)
            val host = endpoint.host.orEmpty()
            val parts = host.split('.').mapNotNull(String::toIntOrNull)
            val tailnet = parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
            require(endpoint.scheme == "https" || tailnet || host.endsWith(".ts.net")) { "Accounts require HTTPS or a private Tailscale connection." }
        }
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, method, privatePath(path),
            body?.toString()?.toByteArray(), "application/json", timeoutMs = timeoutMs)
        if (code !in 200..299) {
            if (path.startsWith("/workspace/agent/accounts")) throw IOException("Account request failed (HTTP $code). Check registration, permissions, the PC connection and its unlocked vault.")
            throw IOException("Computer returned HTTP $code: ${bytes.toString(Charsets.UTF_8).take(500)}")
        }
        if (method == "POST" && (path == "/workspace/jobs" || path.matches(Regex("/workspace/agent/tasks/[a-f0-9]{32}/approve")))) TaskNotifications.enable(context)
        bytes.toString(Charsets.UTF_8)
    }

    /** Streams directly to the PC; never copies the export into phone storage. */
    suspend fun stageChatGptExport(uri: Uri): String = withContext(Dispatchers.IO) {
        val s = settings()
        val connection = java.net.URL(s.searchBaseUrl.trimEnd('/') + "/workspace/memory/imports").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 15000; connection.readTimeout = 180000
            connection.setChunkedStreamingMode(65536)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Authorization", "Bearer " + s.searchApiKey.trim())
            context.contentResolver.openInputStream(uri)?.use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 256L * 1024 * 1024) { "Export exceeds the 256 MB upload limit." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Couldn't open the export.")
            val code = connection.responseCode
            val result = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Export import failed (HTTP $code): " + result.take(300))
            result
        } finally { connection.disconnect() }
    }

    suspend fun upload(file: File, name: String = file.name): String = withContext(Dispatchers.IO) {
        require(file.length() <= 25 * 1024 * 1024) { "File exceeds 25 MB." }
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "POST", privatePath("/workspace/files"),
            file.readBytes(), java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream", mapOf("X-File-Name" to java.net.URLEncoder.encode(name, "UTF-8")), 120000)
        if (code != 200) throw IOException("File upload failed (HTTP $code).")
        JSONObject(bytes.toString(Charsets.UTF_8)).getString("id")
    }

    suspend fun download(id: String, target: File): File = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid file id." }
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", privatePath("/workspace/files/$id"), timeoutMs = 120000)
        if (code != 200) throw IOException("Download failed (HTTP $code).")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target
    }

    suspend fun downloadAgentScreenshot(taskId: String, id: String, target: File): File = withContext(Dispatchers.IO) {
        require(taskId.matches(Regex("[a-f0-9]{32}")) && id.matches(Regex("[a-f0-9]{32}"))) { "Invalid activity screenshot." }
        val s = settings()
        val path = privatePath("/workspace/agent/tasks/$taskId/screenshots/$id")
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", path, timeoutMs = 30000)
        require(code == 200 && bytes.size <= 1024 * 1024) { "Activity screenshot unavailable." }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target
    }

    suspend fun installUpdate(): String = withContext(Dispatchers.IO) {
        val manifest = JSONObject(request("/workspace/release"))
        val pm = context.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedVersion = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed)
        require(manifest.getLong("versionCode") > installedVersion) { "You already have the latest published version." }
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", "/workspace/update", timeoutMs = 120000)
        require(code == 200) { "Update download failed (HTTP $code)." }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == manifest.getString("sha256")) { "Update checksum mismatch." }
        val file = File(context.cacheDir, "downloads/update/assistant.apk")
        file.parentFile?.mkdirs(); file.writeBytes(bytes)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK.")
        require(archive.packageName == context.packageName) { "Update belongs to a different app." }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val certs = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val signer = signatures(installed)
        require(signer.isNotEmpty() && signer == signatures(archive)) { "Update signer doesn't match this app." }
        require(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive) > installedVersion) { "Update version is not newer." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        "Verified update opened in Android's installer."
    }

    suspend fun openArtifact(uri: String) {
        val id = Uri.parse(uri).lastPathSegment ?: return
        val metadata = JSONArray(request("/workspace/files"))
        val info = (0 until metadata.length()).map { metadata.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            ?: throw IOException("That file is no longer available.")
        val name = File(info.getString("name")).name
        val file = download(id, File(context.cacheDir, if (incognito) "incognito/downloads/$id/$name" else "downloads/$id/$name"))
        val content = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(content, info.getString("mime"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
