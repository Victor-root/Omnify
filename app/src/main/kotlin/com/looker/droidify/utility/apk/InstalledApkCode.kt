package com.looker.droidify.utility.apk

import android.content.pm.PackageManager
import com.looker.droidify.utility.common.AppCapabilities
import com.looker.droidify.utility.common.RootDetection
import java.util.zip.ZipFile

/**
 * What an installed app's own code shows it can use, for the badges on its page: root, through one of the
 * libraries in [RootDetection.LIBRARY_MARKERS]. An app offering root as an option can say so nowhere
 * else, neither in its text nor in its permissions.
 *
 * Read from the copy already on the device, which costs no download: the same look at an app not
 * installed yet would mean fetching all of its code (Aurora Store: 12 MB) for a badge.
 */
object InstalledApkCode {

    private val DEX_ENTRY = Regex("classes\\d*\\.dex")

    /** Beyond any real dex file, so a damaged size can't have a whole APK read into memory. */
    private const val MAX_DEX_BYTES = 64L * 1024 * 1024

    /** What [packageName]'s installed code shows it can use, [AppCapabilities.NONE] when it isn't
     *  installed or can't be read. Never throws. Reads the whole code: off the main thread. */
    fun capabilities(packageManager: PackageManager, packageName: String): AppCapabilities {
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return AppCapabilities.NONE
        val apkPath = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return AppCapabilities.NONE
        val root = runCatching {
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX_ENTRY.matches(it.name) && it.size in 1..MAX_DEX_BYTES }
                    .any { entry ->
                        val dex = zip.getInputStream(entry).use { it.readBytes() }
                        DexText.holdsAny(dex, RootDetection.LIBRARY_MARKERS)
                    }
            }
        }.getOrDefault(false)
        return AppCapabilities(root = root, shizuku = false)
    }
}
