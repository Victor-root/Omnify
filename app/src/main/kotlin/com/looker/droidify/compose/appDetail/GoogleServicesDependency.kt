package com.looker.droidify.compose.appDetail

import androidx.annotation.StringRes
import com.looker.droidify.R
import com.looker.droidify.utility.apk.ApkBinaryManifest

/**
 * Detects, from an app's manifest, which specific Google Play Services capabilities it relies on, so
 * the detail screen can warn (precisely, not vaguely) what may not work on a de-Googled device and
 * whether microG covers it.
 *
 * We can only see, per version from the F-Droid index, the merged permission names
 * (`uses-permission` + `uses-permission-sdk-23`), the `uses-feature` names, and the package name.
 * No `<meta-data>`, `<uses-library>` (beyond features), or service declarations. So detection is a
 * curated allow-list of high-signal permission/feature markers.
 *
 * The marker set was chosen for PRECISION over recall: broad markers that many de-Googled apps carry
 * for benign reasons (e.g. `com.google.android.gms.permission.AD_ID`, auto-injected by Firebase/AdMob;
 * `GET_ACCOUNTS`; the whole `com.google.android.gms.permission.` prefix) are deliberately excluded, so
 * the warning means something when it appears.
 */

/** Whether microG re-implements a given Google-services capability. */
enum class MicrogCoverage { FULL, PARTIAL, NONE }

/** A single Google-services capability an app was detected to use. */
data class GoogleServiceDependency(
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
    val coverage: MicrogCoverage,
)

/**
 * Packages that PROVIDE Google services rather than consume them (real GMS, the Services Framework,
 * the Play Store, and microG variants). They declare the very permissions the markers look for, so
 * they would false-fire the warning: microG's GmsCore uses the id `com.google.android.gms` and owns
 * the `gms`/`c2dm`/`gsf` permissions. These bail out of detection entirely.
 */
private val googleServicesProviderPackages = setOf(
    "com.google.android.gms",        // GmsCore (real GMS *and* microG impersonate this id)
    "com.google.android.gsf",        // Google Services Framework / microG GsfProxy
    "com.google.android.gsf.login",  // legacy Google login service
    "com.android.vending",           // Play Store / microG Companion (FakeStore)
    "com.mgoogle.android.gms",       // Vanced/ReVanced microG (GmsCore under a distinct id)
    "org.microg.gms.droidguard",     // microG DroidGuard helper
    "org.microg.nlp",                // microG UnifiedNlp location backend
)

/** How a marker string is compared against a manifest permission/feature name. */
private enum class MatchMode { EXACT, PREFIX, SUFFIX }

/** Where a marker is looked for. */
private enum class MarkerKind { PERMISSION, FEATURE }

/** Groups markers that describe the SAME user-facing capability (e.g. the two push permissions), so a
 *  single line is shown even when an app declares several of them. */
private enum class CapabilityGroup { PUSH, ACTIVITY, MAPS_EMBEDDED, BILLING, LICENSING, SIGNIN, MAPS_V1 }

private class GoogleServiceMarker(
    val group: CapabilityGroup,
    val kind: MarkerKind,
    val match: MatchMode,
    val value: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
    val coverage: MicrogCoverage,
) {
    fun matches(name: String): Boolean = when (match) {
        MatchMode.EXACT -> name == value
        MatchMode.PREFIX -> name.startsWith(value)
        MatchMode.SUFFIX -> name.endsWith(value)
    }
}

/**
 * The vetted markers. Order matters only within a [CapabilityGroup]: the first one that matches wins
 * for that group. The two push markers point at the same strings, so either declaration surfaces one
 * "push" line.
 */
private val googleServiceMarkers = listOf(
    // Firebase Cloud Messaging / legacy GCM push. High signal, fully covered by microG.
    GoogleServiceMarker(
        group = CapabilityGroup.PUSH,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.EXACT,
        value = "com.google.android.c2dm.permission.RECEIVE",
        labelRes = R.string.gms_cap_push,
        descriptionRes = R.string.gms_cap_push_desc,
        coverage = MicrogCoverage.FULL,
    ),
    // The app's own signature-guarded C2DM receiver, named "<packageName>.permission.C2D_MESSAGE" —
    // so it MUST be matched by suffix, never exactly or by prefix (the package name precedes it).
    GoogleServiceMarker(
        group = CapabilityGroup.PUSH,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.SUFFIX,
        value = ".permission.C2D_MESSAGE",
        labelRes = R.string.gms_cap_push,
        descriptionRes = R.string.gms_cap_push_desc,
        coverage = MicrogCoverage.FULL,
    ),
    // Play Services activity recognition / step counting. microG does not implement it.
    GoogleServiceMarker(
        group = CapabilityGroup.ACTIVITY,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.EXACT,
        value = "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
        labelRes = R.string.gms_cap_activity,
        descriptionRes = R.string.gms_cap_activity_desc,
        coverage = MicrogCoverage.NONE,
    ),
    // Reading the GServices provider, the tell-tale of an embedded Google Maps v2 view (among other
    // GMS config uses). microG covers the maps side partially.
    GoogleServiceMarker(
        group = CapabilityGroup.MAPS_EMBEDDED,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.EXACT,
        value = "com.google.android.providers.gsf.permission.READ_GSERVICES",
        labelRes = R.string.gms_cap_maps,
        descriptionRes = R.string.gms_cap_maps_desc,
        coverage = MicrogCoverage.PARTIAL,
    ),
    // Google Play in-app billing. Provided by the Play Store, not microG.
    GoogleServiceMarker(
        group = CapabilityGroup.BILLING,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.EXACT,
        value = "com.android.vending.BILLING",
        labelRes = R.string.gms_cap_billing,
        descriptionRes = R.string.gms_cap_billing_desc,
        coverage = MicrogCoverage.NONE,
    ),
    // Google Play Licensing (LVL). Partially covered (microG/FakeStore can answer some checks).
    GoogleServiceMarker(
        group = CapabilityGroup.LICENSING,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.EXACT,
        value = "com.android.vending.CHECK_LICENSE",
        labelRes = R.string.gms_cap_licensing,
        descriptionRes = R.string.gms_cap_licensing_desc,
        coverage = MicrogCoverage.PARTIAL,
    ),
    // Legacy Google auth-token sign-in ("<...>.GOOGLE_AUTH" bare or service-suffixed) — match by
    // prefix. Partially covered by microG's auth.
    GoogleServiceMarker(
        group = CapabilityGroup.SIGNIN,
        kind = MarkerKind.PERMISSION,
        match = MatchMode.PREFIX,
        value = "com.google.android.googleapps.permission.GOOGLE_AUTH",
        labelRes = R.string.gms_cap_signin,
        descriptionRes = R.string.gms_cap_signin_desc,
        coverage = MicrogCoverage.PARTIAL,
    ),
    // Legacy Google Maps v1 system library (uses-feature). Not on AOSP, not in microG, servers dead.
    GoogleServiceMarker(
        group = CapabilityGroup.MAPS_V1,
        kind = MarkerKind.FEATURE,
        match = MatchMode.EXACT,
        value = "com.google.android.maps",
        labelRes = R.string.gms_cap_maps_v1,
        descriptionRes = R.string.gms_cap_maps_v1_desc,
        coverage = MicrogCoverage.NONE,
    ),
)

/**
 * True when [permissionNames] declares the push (FCM/GCM) capability — the exact same markers the PUSH
 * group above uses, exposed as a plain check so a deeper, per-app verification (does the app's real
 * compiled manifest still carry a *component* reachable through that permission, not merely the
 * permission declaration itself — see [com.looker.droidify.compose.appDetail.AppDetailViewModel]'s own
 * verification) can gate on the identical signal this file's own detection uses, without duplicating the
 * marker values and risking the two drifting apart.
 */
fun declaresPushCapability(permissionNames: Set<String>): Boolean =
    googleServiceMarkers.any { it.group == CapabilityGroup.PUSH && permissionNames.any(it::matches) }

/** Intent actions a Firebase Cloud Messaging / legacy GCM push delivery reaches an app through — the
 *  actions a component must be bound to for the app to receive pushes at all. Shared by both halves of
 *  the push verification (the on-device OS query for an installed app, and the manifest parse for a
 *  remote/cached APK) so they can never drift apart on what "a push component" means. */
val PUSH_INTENT_ACTIONS = listOf(
    "com.google.firebase.MESSAGING_EVENT",
    "com.google.android.c2dm.intent.RECEIVE",
)

/**
 * True when [className] belongs to a bundled Google library's own namespace rather than the app's own
 * code. The distinction matters because Android's manifest merger folds every bundled library's manifest
 * into the app's: the Firebase messaging library always contributes its own generic
 * `FirebaseInstanceIdReceiver`/`FirebaseMessagingService` declarations, which therefore appear in EVERY
 * app that merely *bundles* the library — including a de-Googled fork whose patches removed all of the
 * app's own push services but (naturally) couldn't touch what the library itself declares. Those
 * library components alone can't deliver a push into the app's own code: that takes an app-authored
 * component (in practice a `FirebaseMessagingService` subclass — the only way `onMessageReceived`/
 * `onNewToken` ever reach app code), declared in the app's own namespace.
 */
fun isGoogleLibraryComponent(className: String): Boolean =
    className.startsWith("com.google.firebase.") || className.startsWith("com.google.android.gms.")

/**
 * Whether [manifestBytes] (a compiled AndroidManifest.xml, from any source — a remote range-read, a
 * cached APK on disk) declares NO app-authored component bound to a push intent action — i.e. the push
 * permission this app declares is a vestigial leftover (a bundled library's residue) rather than a
 * capability its own code can actually use. Null when the manifest can't be parsed at all — the caller
 * should change nothing then. See [isGoogleLibraryComponent] for why library-namespace components are
 * excluded rather than counted, confirmed against a real de-Googled fork's production manifest.
 */
fun pushCapabilityIsVestigial(manifestBytes: ByteArray): Boolean? {
    val components = ApkBinaryManifest.components(manifestBytes) ?: return null
    return components
        .filter { component -> component.actions.any { it in PUSH_INTENT_ACTIONS } }
        .all { isGoogleLibraryComponent(it.className) }
}

/**
 * The Google-services capabilities [packageName] depends on, given its manifest [permissionNames] and
 * [featureNames]. Empty when the app is itself a Google-services provider (microG/GMS/Play Store) or
 * declares none of the vetted markers.
 *
 * Ordered most-impactful first (uncovered by microG, then partial, then fully covered), so the firmest
 * warnings sit at the top of the card.
 */
fun detectGoogleServicesDependencies(
    packageName: String,
    permissionNames: Set<String>,
    featureNames: Set<String>,
): List<GoogleServiceDependency> {
    if (packageName in googleServicesProviderPackages) return emptyList()
    val byGroup = LinkedHashMap<CapabilityGroup, GoogleServiceDependency>()
    for (marker in googleServiceMarkers) {
        if (byGroup.containsKey(marker.group)) continue
        val haystack = if (marker.kind == MarkerKind.FEATURE) featureNames else permissionNames
        if (haystack.any(marker::matches)) {
            byGroup[marker.group] = GoogleServiceDependency(
                labelRes = marker.labelRes,
                descriptionRes = marker.descriptionRes,
                coverage = marker.coverage,
            )
        }
    }
    // Most-impactful first: NONE (0) before PARTIAL (1) before FULL (2).
    return byGroup.values.sortedBy {
        when (it.coverage) {
            MicrogCoverage.NONE -> 0
            MicrogCoverage.PARTIAL -> 1
            MicrogCoverage.FULL -> 2
        }
    }
}
