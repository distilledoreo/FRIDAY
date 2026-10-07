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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

/** Uses the existing authenticated computer endpoint; no connected-service accounts. */
class WorkspaceClient(private val context: Context, private val settings: () -> ServerSettings) : WorkspaceGateway {
    val imageSettings = ImageSettingsStore(context)
    val imageStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override suspend fun toolRequest(path: String, method: String, body: String?): String {
        val payload = body?.let(::JSONObject)
        if (path == "/workspace/images" && method == "POST" && payload != null) imageSettings.apply(payload, serverIdentity)
        val result = request(path, method, payload)
        if (path.startsWith("/workspace/images/") && method == "GET") {
            val job = JSONObject(result)
            imageStatus.value = if (job.optString("status") in listOf("completed", "failed", "cancelled")) null else job.optString("phase").replace('_', ' ')
        }
        if (path.startsWith("/workspace/images/") && path.endsWith("/cancel")) imageStatus.value = null
        return result
    }
    val serverIdentity: String get() = settings().searchBaseUrl.trim().trimEnd('/')
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, method, path,
            body?.toString()?.toByteArray(), "application/json", timeoutMs = 120000)
        if (code !in 200..299) throw IOException("Computer returned HTTP $code: ${bytes.toString(Charsets.UTF_8).take(500)}")
        if (path == "/workspace/jobs" && method == "POST") TaskNotifications.enable(context)
        bytes.toString(Charsets.UTF_8)
    }

    suspend fun upload(file: File, name: String = file.name): String = withContext(Dispatchers.IO) {
        require(file.length() <= 25 * 1024 * 1024) { "File exceeds 25 MB." }
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "POST", "/workspace/files",
            file.readBytes(), java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream", mapOf("X-File-Name" to java.net.URLEncoder.encode(name, "UTF-8")), 120000)
        if (code != 200) throw IOException("File upload failed (HTTP $code).")
        JSONObject(bytes.toString(Charsets.UTF_8)).getString("id")
    }

    suspend fun download(id: String, target: File): File = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid file id." }
        val s = settings()
        val (code, bytes) = desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", "/workspace/files/$id", timeoutMs = 120000)
        if (code != 200) throw IOException("Download failed (HTTP $code).")
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
        val file = download(id, File(context.cacheDir, "downloads/$id/$name"))
        val content = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(content, info.getString("mime"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
