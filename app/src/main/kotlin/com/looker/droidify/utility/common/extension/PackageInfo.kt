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

/** Friendly name of the app that installed [packageName] (Play, F-Droid, this app…), the raw
 *  installer id, or a generic label for a sideloaded app with no recorded installer. Shared between
 *  the F-Droid catalogue and external-source detail pages, so both surface where an update would
 *  actually come from (useful to spot e.g. an app installed by a different client that can't be
 *  updated in place across a signing-key mismatch). */
fun Context.installerSourceLabel(packageName: String): String {
    val installer = runCatching {
        if (SdkCheck.isR) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()
    return when (installer) {
        null, "" -> getString(R.string.installer_unknown)
        "com.android.vending" -> "Google Play"
        "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid"
        this.packageName -> getString(R.string.installer_self_name)
        else -> installer
    }
}

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
