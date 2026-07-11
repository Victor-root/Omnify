package com.looker.droidify.installer.installers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.looker.droidify.utility.common.extension.getLauncherActivities
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.intent
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui
import kotlin.coroutines.resume

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 87263

fun launchShizuku(context: Context) {
    val packageName = context.shizukuPackageName()
        ?: ShizukuProvider.MANAGER_APPLICATION_ID
    val activities = context.packageManager.getLauncherActivities(packageName)
    if (activities.isEmpty()) return
    val intent = intent(Intent.ACTION_MAIN) {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setComponent(
            ComponentName(
                packageName,
                activities.first().first,
            ),
        )
        setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun initSui(context: Context) = Sui.init(context.packageName)

fun isSuiAvailable() = Sui.isSui()

private fun Context.shizukuPermissionInfo() =
    runCatching {
        packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
    }.getOrNull()

private fun Context.shizukuPackageName() = shizukuPermissionInfo()?.packageName

fun isShizukuInstalled(context: Context) =
    context.shizukuPermissionInfo() != null ||
        context.packageManager.getPackageInfoCompat(ShizukuProvider.MANAGER_APPLICATION_ID) != null

fun isShizukuAlive() = Shizuku.pingBinder()

fun isShizukuGranted() = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

suspend fun requestPermissionListener() = suspendCancellableCoroutine {
    val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            it.resume(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }
    Shizuku.addRequestPermissionResultListener(listener)
    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    it.invokeOnCancellation {
        Shizuku.removeRequestPermissionResultListener(listener)
    }
}

fun requestShizuku() {
    Shizuku.shouldShowRequestPermissionRationale()
    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
}

private const val ROOT_TAG = "RootInstaller"

fun isMagiskGranted(): Boolean {
    // getShell() throws (NoShellException) rather than just returning false when it can't obtain a
    // root shell at all (su missing, request denied outright, timed out, …) — left uncaught, that
    // exception was silently swallowed by whatever coroutine called this, so switching to Root looked
    // like it simply did nothing instead of failing with a reason.
    val shell = runCatching {
        com.topjohnwu.superuser.Shell.getCachedShell() ?: com.topjohnwu.superuser.Shell.getShell()
    }.onFailure {
        android.util.Log.w(ROOT_TAG, "Couldn't obtain a root shell", it)
    }.getOrNull()
    val granted = com.topjohnwu.superuser.Shell.isAppGrantedRoot()
    android.util.Log.d(
        ROOT_TAG,
        "isMagiskGranted: shellStatus=${shell?.status} isAppGrantedRoot=$granted",
    )
    return granted == true
}
