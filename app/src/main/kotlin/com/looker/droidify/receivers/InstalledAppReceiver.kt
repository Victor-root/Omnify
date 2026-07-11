package com.looker.droidify.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.extension.toInstalledItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InstalledAppReceiver(
    private val packageManager: PackageManager,
    private val installedRepository: InstalledRepository,
    private val scope: CoroutineScope,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName =
            intent.data?.let { if (it.scheme == "package") it.schemeSpecificPart else null }
        if (packageName != null) {
            when (intent.action.orEmpty()) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                -> {
                    val packageInfo = packageManager.getPackageInfoCompat(packageName)
                    if (packageInfo != null) {
                        val installedItem = packageInfo.toInstalledItem()
                        scope.launch { installedRepository.put(installedItem) }
                    } else {
                        scope.launch { installedRepository.delete(packageName) }
                    }
                }
            }
        }
    }
}
