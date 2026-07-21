package com.looker.droidify.data

import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.model.InstalledItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE one definition of "this installed signing certificate doesn't belong to any signer this source
 * declares" — i.e. the app occupying this package name on the device is a *different* app that merely
 * shares the name (Android hands a package name to whoever installs first; a de-Googled fork shipping
 * under an upstream app's real package id is a confirmed real case), not a build of the entry showing
 * it. Every screen and worker that needs this comparison calls this ONE function, so the rule (both
 * sides lowercase-hex SHA-256; case-insensitive; and crucially, never flag a mismatch when either side
 * has nothing to compare — an unconfirmed mismatch is worse than a missed one) can never drift apart
 * between call sites again. This exact drift already happened once: the detail screen, the app-list
 * tiles and the repo-detail grid each grew their own copy of an "is it installed" check, and fixing
 * the identity rule in one left stale checkmarks in the others until each was hunted down separately.
 */
fun signerMismatch(installedSigner: String?, declaredSigners: Collection<String>): Boolean {
    val signer = installedSigner?.takeIf { it.isNotBlank() } ?: return false
    if (declaredSigners.isEmpty()) return false
    return declaredSigners.none { it.equals(signer, ignoreCase = true) }
}

/**
 * The single source of truth for "which catalogue apps count as installed on this device" — by package
 * name alone. Every catalogue-side "installed" signal (detail-screen install state, list/grid tile
 * checkmarks, the Installed tab, a repository page's app grid) derives from this one flow, so they can
 * never disagree with each other, and a future change to the rule lands everywhere at once.
 *
 * Deliberately NOT filtered by [signerMismatch]: a package name occupied by a differently-signed build
 * (the same app from a different distribution channel — Google Play vs. this repo, say — is the common
 * case; a genuinely unrelated app squatting the name is rare) still counts as installed here, so it
 * shows up normally in the Installed tab and offers updates like any other installed app. The signer
 * check itself still runs, independently, wherever a screen needs to warn about it or decide whether an
 * update can actually be applied in place ([signerMismatch]'s other call sites: the detail screens' own
 * warning footer, [com.looker.droidify.compose.appList.AppListViewModel]'s update-suppression-for-
 * system-apps rule, the update worker) — this flow only answers "is something here at all", not "is it
 * provably the same build".
 */
@Singleton
class InstalledIdentityRepository @Inject constructor(
    installedRepository: InstalledRepository,
    appRepository: AppRepository,
    @ApplicationScope scope: CoroutineScope,
) {

    /**
     * packageName -> the installed app's [InstalledItem], live: re-emits on any install/uninstall (via
     * [InstalledRepository]'s stream, kept current by InstalledAppReceiver). Still combined with catalogue
     * changes (kept as an input, though no longer used to filter) so a sync-triggered signer change
     * doesn't leave a stale emission. Shared across every subscriber rather than recomputed per
     * collector.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val verifiedInstalled: Flow<Map<String, InstalledItem>> = combine(
        installedRepository.getAllStream(),
        appRepository.catalogChanges.mapLatest { appRepository.suggestedVersions() },
    ) { items, _ ->
        items.associateBy { it.packageName }
    }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), replay = 1)

    private companion object {
        /** Keeps the shared computation alive across quick screen switches (every catalogue screen
         *  subscribes to this) without keeping the database subscriptions hot forever when no UI is. */
        const val SHARE_TIMEOUT_MS = 5_000L
    }
}
