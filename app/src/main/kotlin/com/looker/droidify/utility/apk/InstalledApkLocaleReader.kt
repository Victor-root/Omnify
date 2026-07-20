package com.looker.droidify.utility.apk

import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * Reads an installed package's real, boilerplate-filtered supported locales straight from its base
 * APK's own compiled `resources.arsc` on disk, instead of trusting `AssetManager.getLocales()`'s raw,
 * unfiltered locale-qualified-config list — that list can't distinguish a bundled dependency's own
 * pre-translated locales (AndroidX, Material3, …) from the app's own, which [ApkResourceLocales] was
 * specifically built to filter out (see its own doc comment). Confirmed real: an installed, English-only
 * app built on ordinary AndroidX/Material3 dependencies reports dozens of "supported" locales through
 * AssetManager alone, none of them the app's own.
 *
 * Runs the exact same [ApkResourceLocales] logic a not-yet-installed release's downloaded APK goes
 * through ([RemoteApkLocaleReader]) — the base APK is already on disk, so no network read is needed, just
 * the one ZIP entry.
 */
object InstalledApkLocaleReader {

    private const val ENTRY_NAME = "resources.arsc"

    /**
     * [packageName]'s real supported locale codes, or null when the package, its base APK, or
     * `resources.arsc` can't be read at all — the caller should treat that as "couldn't determine,"
     * never as "no locales" (mirrors [ApkResourceLocales.localeCodes]'s own null contract). Never throws.
     */
    fun fetchLocales(packageManager: PackageManager, packageName: String): List<String>? {
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        val apkPath = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return null
        val arscBytes = runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(ENTRY_NAME) ?: return@use null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull() ?: return null
        return ApkResourceLocales.localeCodes(arscBytes)
    }
}
