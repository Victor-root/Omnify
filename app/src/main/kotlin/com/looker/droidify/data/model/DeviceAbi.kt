package com.looker.droidify.data.model

import android.os.Build

/** ABIs this device can execute (native bridges included), as reported by the platform. */
private val deviceAbis: Set<String> = Build.SUPPORTED_ABIS.toSet()

/** This device's preferred ABI: its first 64-bit ABI, falling back to the first 32-bit one. */
private val devicePrimaryAbi: String? = Build.SUPPORTED_64_BIT_ABIS?.firstOrNull()
    ?: Build.SUPPORTED_32_BIT_ABIS?.firstOrNull()

/**
 * Whether this device can install [this] release: its declared SDK range must include the running
 * version and, when it carries native code, it must ship a library for one of the device's ABIs.
 */
fun Package.isInstallableOnDevice(sdk: Int = Build.VERSION.SDK_INT): Boolean {
    val min = manifest.usesSDKs.min
    val max = manifest.usesSDKs.max
    if (min > 0 && sdk < min) return false
    if (max > 0 && sdk > max) return false
    val abis = platforms.value
    return abis.isEmpty() || abis.any { it in deviceAbis }
}

/**
 * Picks the best release to install on *this* device, or `null` when none is compatible.
 *
 * Multi-ABI apps such as VLC publish one APK per ABI under *different* version codes (…04 arm64-v8a,
 * …03 x86_64, …02 x86), and the repo's [suggested] code is merely the highest of them (arm64).
 * Taking the highest blindly would push the arm64 APK onto an x86 device and fail the install with
 * `INSTALL_FAILED_NO_MATCHING_ABIS`, so we filter to device-compatible releases first, then take the
 * newest at or below [suggested], breaking ties toward the primary (64-bit) ABI and then the most
 * specific APK. This mirrors the legacy engine's release selection.
 */
fun <T> List<Pair<Package, T>>.selectForDevice(suggested: Long): Pair<Package, T>? {
    val compatible = filter { (pkg, _) -> pkg.isInstallableOnDevice() }
    return compatible
        .filter { (pkg, _) -> suggested <= 0 || pkg.manifest.versionCode <= suggested }
        .ifEmpty { compatible }
        .maxWithOrNull(
            compareBy<Pair<Package, T>> { (pkg, _) -> pkg.manifest.versionCode }
                .thenBy { (pkg, _) ->
                    if (devicePrimaryAbi != null && devicePrimaryAbi in pkg.platforms.value) 1 else 0
                }
                .thenByDescending { (pkg, _) -> pkg.platforms.value.size },
        )
}
