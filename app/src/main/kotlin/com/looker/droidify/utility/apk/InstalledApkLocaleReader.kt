package com.looker.droidify.utility.apk

import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * Reads an installed package's real, boilerplate-filtered supported locales straight from its base
 * APK on disk, instead of trusting `AssetManager.getLocales()`'s raw, unfiltered locale-qualified-config
 * list — that list can't distinguish a bundled dependency's own pre-translated locales (AndroidX,
 * Material3, …) from the app's own, which [ApkResourceLocales] was specifically built to filter out (see
 * its own doc comment). Confirmed real: an installed, English-only app built on ordinary AndroidX/
 * Material3 dependencies reports dozens of "supported" locales through AssetManager alone, none of them
 * the app's own.
 *
 * Runs the exact same detection a not-yet-installed release's downloaded APK goes through
 * ([RemoteApkLocaleReader]): [ApkResourceLocales] over the compiled `resources.arsc`, UNIONED with
 * [RemoteApkLocaleReader.assetLocalesFromEntryNames] over the APK's own ZIP entry names — a genuinely
 * split-installed app's own per-language config split (see [com.looker.droidify.compose.appDetail.
 * AppDetailViewModel.isInstalledViaSplitApks]) can carry its real per-locale files at that path, entirely
 * invisible to [ApkResourceLocales] alone.
 *
 * Reads only THIS package's base APK — a package installed via Android App Bundle language splits (the
 * OS only ever fetches the splits matching the device's own configured languages) can never have its
 * *complete* language list answered from the base APK alone, whatever this function finds. Callers that
 * care about the complete picture, not just what's on this specific device, cross-check a split install
 * against the app's real release separately (see [com.looker.droidify.compose.appDetail.
 * AppDetailViewModel.trackRemoteApkLocales]) rather than expecting this function to see beyond the base
 * APK it was given.
 */
object InstalledApkLocaleReader {

    private const val ENTRY_NAME = "resources.arsc"

    /**
     * [packageName]'s real supported locale codes, or null when the package or its base APK can't be
     * read at all — the caller should treat that as "couldn't determine," never as "no locales" (mirrors
     * [ApkResourceLocales.localeCodes]'s own null contract). Never throws.
     */
    fun fetchLocales(packageManager: PackageManager, packageName: String): List<String>? {
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        val apkPath = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return null
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val assetLocales = RemoteApkLocaleReader.assetLocalesFromEntryNames(
                    zip.entries().asSequence().map { it.name }.toList(),
                )
                val arscLocales = zip.getEntry(ENTRY_NAME)?.let { entry ->
                    ApkResourceLocales.localeCodes(zip.getInputStream(entry).use { it.readBytes() })
                }
                // Union, not "prefer one over the other" — see this object's own doc comment for why
                // either signal alone can miss real locales the other one catches.
                (arscLocales.orEmpty() + assetLocales).distinct().sorted().ifEmpty { arscLocales }
            }
        }.getOrNull()
    }
}
