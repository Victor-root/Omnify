package com.looker.droidify.installer.installers.shizuku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.looker.droidify.R
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.uninstallPackage
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.size
import com.looker.droidify.utility.common.log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStream
import kotlin.coroutines.resume

class ShizukuInstaller(private val context: Context) : Installer {

    companion object {
        private val SESSION_ID_REGEX = Regex("(?<=\\[).+?(?=])")
        private const val TAG = "ShizukuInstaller"
    }

    private fun toast(resId: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, context.getString(resId), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Safety net: Shizuku must be running and have granted Omnify permission, otherwise
     * Shizuku.newProcess() throws immediately. The install flows already check this before downloading
     * (see [ShizukuState.installBlockReason]); this re-check covers Shizuku dying between that check and
     * the actual install, and shows the same short message. Returns false when it isn't usable.
     */
    private fun shizukuReady(): Boolean {
        val readiness = ShizukuState.readiness(context)
        if (readiness == ShizukuState.Readiness.READY) return true
        log("Shizuku not ready: $readiness", TAG, Log.WARN)
        when (readiness) {
            ShizukuState.Readiness.NOT_INSTALLED -> toast(R.string.shizuku_not_installed)
            ShizukuState.Readiness.NOT_RUNNING -> toast(R.string.shizuku_not_running)
            ShizukuState.Readiness.NO_PERMISSION -> {
                ShizukuState.requestPermission()
                toast(R.string.shizuku_permission_needed)
            }
            ShizukuState.Readiness.READY -> Unit
        }
        return false
    }

    /**
     * The shell process currently being awaited. Tracked so that a cancellation (user pressing
     * "Cancel" or the queue-level install timeout firing) can [Process.destroy] it and unblock the
     * otherwise blocking [Process.waitFor], instead of hanging the install queue forever (#781).
     */
    @Volatile
    private var runningProcess: Process? = null

    override suspend fun install(
        installItem: InstallItem,
    ): InstallState = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { runningProcess?.destroy() } }
        if (!shizukuReady()) {
            cont.resume(InstallState.Failed)
            return@suspendCancellableCoroutine
        }
        var sessionId: String? = null
        val file = Cache.getReleaseFile(context, installItem.installFileName)
        try {
            val fileSize = file.length()
            if (fileSize == 0L) {
                cont.cancel()
                error("File is not valid: Size ${file.size}")
            }
            if (cont.isCompleted) return@suspendCancellableCoroutine
            val installerPackage = context.packageName
            file.inputStream().use {
                // INSTALL_REASON_USER (4): launchers only auto-add a home screen icon for
                // user-initiated install sessions; `pm` knows the option since Android O.
                val createCommand = when {
                    SdkCheck.isOreo ->
                        "pm install-create --user current -i $installerPackage" +
                            " --install-reason 4 -S $fileSize"
                    SdkCheck.isNougat ->
                        "pm install-create --user current -i $installerPackage -S $fileSize"
                    else ->
                        "pm install-create -i $installerPackage -S $fileSize"
                }
                val createResult = exec(createCommand)
                sessionId = SESSION_ID_REGEX.find(createResult.out)?.value
                    ?: run {
                        cont.cancel()
                        error("Failed to create install session")
                    }
                if (cont.isCompleted) return@suspendCancellableCoroutine

                val writeResult = exec("pm install-write -S $fileSize $sessionId base -", it)
                if (writeResult.resultCode != 0) {
                    cont.cancel()
                    error("Failed to write APK to session $sessionId")
                }
                if (cont.isCompleted) return@suspendCancellableCoroutine

                val commitResult = exec("pm install-commit $sessionId")
                if (commitResult.resultCode != 0) {
                    cont.cancel()
                    error("Failed to commit install session $sessionId")
                }
                if (cont.isCompleted) return@suspendCancellableCoroutine
                cont.resume(InstallState.Installed)
            }
        } catch (e: Exception) {
            log("Install failed for ${installItem.packageName.name}: $e", TAG, Log.ERROR)
            if (sessionId != null) runCatching { exec("pm install-abandon $sessionId") }
            if (cont.isActive) cont.resume(InstallState.Failed)
        }
    }

    override suspend fun uninstall(packageName: PackageName) =
        context.uninstallPackage(packageName)

    override fun close() {
        runCatching { runningProcess?.destroy() }
    }

    private data class ShellResult(val resultCode: Int, val out: String)

    // Shizuku's own suggested replacement (a bound AIDL UserService) is a fundamentally different
    // execution model, not a drop-in call — out of scope for a warning cleanup.
    @Suppress("DEPRECATION")
    private fun exec(command: String, stdin: InputStream? = null): ShellResult {
        val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        runningProcess = process
        try {
            if (stdin != null) {
                process.outputStream.use { stdin.copyTo(it) }
            }
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val resultCode = process.waitFor()
            return ShellResult(resultCode, output)
        } finally {
            runningProcess = null
        }
    }
}
