package com.localfirst.assistant.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lets tools ask for runtime permissions while a chat turn is running. The
 * activity attaches its launcher; a tool calls [ensure] and suspends until the
 * user answers the system dialog. With no activity attached, requests are denied.
 */
object PermissionBroker {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Map<String, Boolean>>? = null
    private val mutex = Mutex()

    /** Call from the activity's onCreate, before it starts. */
    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pending?.complete(result)
            pending = null
        }
    }

    fun detach() {
        launcher = null
        pending?.complete(emptyMap())
        pending = null
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when every permission is granted, asking for the missing ones first. */
    suspend fun ensure(context: Context, vararg permissions: String): Boolean {
        val missing = permissions.filterNot { isGranted(context, it) }
        if (missing.isEmpty()) return true
        return mutex.withLock {
            val result = withContext(Dispatchers.Main) {
                val launch = launcher ?: return@withContext emptyMap()
                val answer = CompletableDeferred<Map<String, Boolean>>()
                pending = answer
                launch.launch(missing.toTypedArray())
                answer.await()
            }
            missing.all { result[it] == true || isGranted(context, it) }
        }
    }
}
