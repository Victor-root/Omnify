package com.looker.droidify.utility.common.extension

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.looker.droidify.R
import com.looker.droidify.data.encryption.sha256
import com.looker.droidify.data.model.hex
import com.looker.droidify.utility.common.SdkCheck
import java.io.File

val PackageInfo.singleSignature: Signature?
    get() = if (SdkCheck.isPie) {
        val signingInfo = signingInfo
        if (signingInfo?.hasMultipleSigners() == false) {
            signingInfo.apkContentsSigners
                ?.let { if (it.size == 1) it[0] else null }
        } else {
            null
        }
    } else {
        @Suppress("DEPRECATION")
        signatures?.let { if (it.size == 1) it[0] else null }
    }

fun Signature.calculateHash() = sha256(toByteArray()).hex()

@Suppress("DEPRECATION")
val PackageInfo.versionCodeCompat: Long
    get() = if (SdkCheck.isPie) longVersionCode else versionCode.toLong()

fun PackageManager.isSystemApplication(packageName: String): Boolean = try {
    (
        (
            this.getApplicationInfoCompat(packageName)
                .flags
            ) and ApplicationInfo.FLAG_SYSTEM
        ) != 0
} catch (e: Exception) {
    false
}

fun PackageManager.getLauncherActivities(packageName: String): List<Pair<String, String>> {
    return queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(
            Intent.CATEGORY_LAUNCHER,
        ),
        0,
    )
        .asSequence()
        .mapNotNull { resolveInfo -> resolveInfo.activityInfo }
        .filter { activityInfo -> activityInfo.packageName == packageName }
        .mapNotNull { activityInfo ->
            val label = try {
                activityInfo.loadLabel(this).toString()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            label?.let { labelName ->
                activityInfo.name to labelName
            }
        }
        .toList()
}

fun PackageManager.getApplicationInfoCompat(
    filePath: String,
): ApplicationInfo = if (SdkCheck.isTiramisu) {
    getApplicationInfo(
        filePath,
        PackageManager.ApplicationInfoFlags.of(0L),
    )
} else {
    getApplicationInfo(filePath, 0)
}

@Suppress("DEPRECATION")
private val signaturesFlagCompat: Int
    get() = (
        if (SdkCheck.isPie) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }
        ) or PackageManager.GET_SIGNATURES

fun PackageManager.getPackageInfoCompat(
    packageName: String,
    signatureFlag: Int = signaturesFlagCompat,
): PackageInfo? = try {
    if (SdkCheck.isTiramisu) {
        getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(signatureFlag.toLong()),
        )
    } else {
        getPackageInfo(packageName, signatureFlag)
    }
} catch (e: Exception) {
    null
}

fun PackageManager.getPackageName(
    packageName: String?,
): CharSequence? {
    if (packageName == null) return null
    return try {
        getApplicationLabel(
            getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA,
            ),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

fun PackageManager.getPackageArchiveInfoCompat(
    filePath: String,
    signatureFlag: Int = signaturesFlagCompat,
): PackageInfo? = try {
    if (SdkCheck.isTiramisu) {
        getPackageArchiveInfo(
            filePath,
            PackageManager.PackageInfoFlags.of(signatureFlag.toLong()),
        )
    } else {
        getPackageArchiveInfo(filePath, signatureFlag)
    }
} catch (e: Exception) {
    null
}

private fun Context.rawInstallerPackageName(packageName: String): String? = runCatching {
    if (SdkCheck.isR) {
        packageManager.getInstallSourceInfo(packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(packageName)
    }
}.getOrNull()

/** Friendly name of the app that installed [packageName] (Play, F-Droid, this app…), the raw
 *  installer id, or a generic label for a sideloaded app with no recorded installer. Shared between
 *  the F-Droid catalogue and external-source detail pages, so both surface where an update would
 *  actually come from (useful to spot e.g. an app installed by a different client that can't be
 *  updated in place across a signing-key mismatch).
 *
 *  [knownInstalledByOmnify] is the caller's own confirmation that Omnify itself installed the exact
 *  version currently on the device, used only when Android reports no installer of its own: this has
 *  been observed to happen for an app Omnify genuinely installed once Omnify itself is later fully
 *  uninstalled and reinstalled, even though the app in question was never touched. Android's own
 *  answer still wins whenever it names an actual installer (including a *different* one), since that
 *  is real information that something else has since taken over the package name. */
fun Context.installerSourceLabel(packageName: String, knownInstalledByOmnify: Boolean = false): String {
    return when (val installer = rawInstallerPackageName(packageName)) {
        null, "" -> if (knownInstalledByOmnify) {
            getString(R.string.installer_self_name)
        } else {
            getString(R.string.installer_unknown)
        }
        "com.android.vending" -> "Google Play"
        "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid"
        this.packageName -> getString(R.string.installer_self_name)
        else -> installer
    }
}

/** True when [packageName]'s installer is Google Play itself. The strongest signal (short of the
 *  signing certificate, which callers already compare separately) that what's installed under a
 *  Google-services-provider id (see [com.looker.droidify.compose.appDetail.isGoogleServicesProviderPackage])
 *  is genuinely Google's own build, not e.g. a ROM-integrated microG that impersonates the same id. */
fun Context.isInstalledFromGooglePlay(packageName: String): Boolean =
    rawInstallerPackageName(packageName) == "com.android.vending"

/**
 * True when [packageName] is already installed but signed by a different key than [apkFile]. Android
 * refuses to update an app across signers (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so callers use this to
 * detect the conflict up front and offer an uninstall instead of letting the system installer fail.
 * Returns false when the app isn't installed or when either set of signatures can't be read (don't
 * block on uncertainty). Shared by the F-Droid catalogue and external-source install flows.
 */
@Suppress("DEPRECATION", "PackageManagerGetSignatures")
fun PackageManager.installedWithDifferentSignature(packageName: String, apkFile: File): Boolean {
    val installedSignatures = signaturesOf { flags ->
        runCatching { getPackageInfo(packageName, flags) }.getOrNull()
    }
    if (installedSignatures.isEmpty()) return false
    val apkSignatures = signaturesOf { flags ->
        runCatching { getPackageArchiveInfo(apkFile.absolutePath, flags) }.getOrNull()
    }
    if (apkSignatures.isEmpty()) return false
    return installedSignatures.intersect(apkSignatures).isEmpty()
}

/**
 * True when [packageName] is already installed under a higher version code than [apkFile]. Android
 * refuses to install an update with a lower version code (INSTALL_FAILED_VERSION_DOWNGRADE) — e.g.
 * picking an older release from a version-history list while a newer one is already installed — so
 * callers use this to detect the conflict up front and offer an uninstall instead of letting the
 * system installer fail, the same treatment as [installedWithDifferentSignature]. Returns false when
 * the app isn't installed or when either version code can't be read (don't block on uncertainty).
 */
fun PackageManager.isVersionDowngrade(packageName: String, apkFile: File): Boolean {
    val installedVersion = runCatching { getPackageInfo(packageName, 0) }
        .getOrNull()?.versionCodeCompat ?: return false
    val apkVersion = runCatching { getPackageArchiveInfo(apkFile.absolutePath, 0) }
        .getOrNull()?.versionCodeCompat ?: return false
    return apkVersion < installedVersion
}

/** Signing certificates of a package, as hex strings, using the right API for the SDK level. */
@Suppress("DEPRECATION", "PackageManagerGetSignatures", "NewApi")
private fun signaturesOf(getInfo: (flags: Int) -> PackageInfo?): Set<String> {
    val flags = if (SdkCheck.isPie) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val info = getInfo(flags) ?: return emptySet()
    val signatures = if (SdkCheck.isPie) info.signingInfo?.apkContentsSigners else info.signatures
    return signatures?.mapNotNull { it?.toCharsString() }?.toSet().orEmpty()
}

fun PackageManager.getInstalledPackagesCompat(
    signatureFlag: Int = signaturesFlagCompat,
): List<PackageInfo>? = try {
    if (SdkCheck.isTiramisu) {
        getInstalledPackages(PackageManager.PackageInfoFlags.of(signatureFlag.toLong()))
    } else {
        getInstalledPackages(signatureFlag)
    }
} catch (e: Exception) {
    null
}
