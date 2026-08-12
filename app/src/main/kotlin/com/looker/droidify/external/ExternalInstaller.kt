package com.looker.droidify.external

import android.content.Context
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.installedWithDifferentSignature
import com.looker.droidify.utility.common.extension.isVersionDowngrade
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What became of an attempt to install an external app's latest release without a user present. */
enum class ExternalInstallOutcome {
    /** Handed to the installer. Whether Android then asks the user to confirm depends on the chosen
     *  install method, not on us: Shizuku and root install silently, and the default session installer
     *  asks Android to as well, which it grants for a same-signer update on recent versions. */
    STARTED,

    /** The source has no release APK recorded to install. */
    NO_RELEASE,

    /** The download or the APK itself was unusable. */
    FAILED,

    /**
     * The latest release installs under a different package id than the copy on the device. A real
     * case, not a theoretical one: brave/brave-browser publishes Stable and Beta from the same repo,
     * under com.brave.browser and com.brave.browser_beta. Updating on its own would mean silently
     * installing a *different app* alongside the one the user has, so it is left for them to decide.
     */
    DIFFERENT_PACKAGE,

    /** Nothing is installed under this release's package id, so there is no update to carry out. An
     *  automatic pass updates what is on the device; it never installs something new by itself. */
    NOT_INSTALLED,

    /**
     * Android can't carry this out in place: the release is signed by another key, or is older than
     * what's installed. Both are resolvable by uninstalling first, which needs the user to confirm, so
     * the app's own page keeps offering it and the automatic pass leaves it alone.
     */
    NEEDS_USER,
}

/**
 * Installing an external app's release APK, and recording what that install left on the device.
 *
 * The record-keeping half is shared with [com.looker.droidify.compose.externalApps.ExternalAppsViewModel]'s
 * own install flow rather than written twice: which release a source is now on is what every later update
 * check compares against, and two copies of that rule drifting apart is exactly how an app ends up stuck
 * offering an update it has already installed (or, worse, losing track of the copy on the device).
 */
@Singleton
class ExternalInstaller @Inject constructor(
    private val repository: ExternalAppRepository,
    private val refresher: ExternalRefresher,
    private val downloader: Downloader,
    private val installManager: InstallManager,
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Downloads and installs [app]'s latest recorded release, with no UI and no prompting.
     *
     * Deliberately uses the `latest*` fields the refresher already resolved instead of asking the
     * provider again: the pass that decided this app has an update has just run, and re-fetching would
     * spend another API call to learn the same thing.
     */
    suspend fun installLatest(app: ExternalApp): ExternalInstallOutcome {
        val url = app.latestApkUrl ?: return ExternalInstallOutcome.NO_RELEASE
        val cacheFileName = releaseCacheFileName(app, app.latestTag)
        val releaseFile = Cache.getReleaseFile(context, cacheFileName)
        // Download to a partial file and promote it on success. The Downloader resumes by Range against
        // the target's current size, so a previously-completed file would make it request past EOF ->
        // HTTP 416 -> failure. Start each download fresh (asset URLs are one-shot CDN links anyway).
        val partial = Cache.getPartialReleaseFile(context, cacheFileName)
        partial.delete()
        val response = downloader.downloadToFile(url = url, target = partial)
        if (response !is NetworkResponse.Success) {
            partial.delete()
            Log.w(TAG, "Download failed for ${app.key}")
            return ExternalInstallOutcome.FAILED
        }
        partial.copyTo(releaseFile, overwrite = true)
        partial.delete()

        // External APKs aren't pre-registered like F-Droid ones, so the package id comes from the file
        // we just downloaded.
        val packageName = context.packageManager
            .getPackageArchiveInfo(releaseFile.absolutePath, 0)
            ?.packageName
        if (packageName == null) {
            Log.w(TAG, "Unreadable APK for ${app.key}")
            return ExternalInstallOutcome.FAILED
        }
        val tracked = app.packageName
        if (tracked != null && tracked != packageName) {
            Log.i(TAG, "Skipping ${app.key}: release is $packageName, tracked copy is $tracked")
            return ExternalInstallOutcome.DIFFERENT_PACKAGE
        }
        if (!refresher.isInstalled(packageName)) {
            return ExternalInstallOutcome.NOT_INSTALLED
        }
        if (context.packageManager.installedWithDifferentSignature(packageName, releaseFile) ||
            context.packageManager.isVersionDowngrade(packageName, releaseFile)
        ) {
            return ExternalInstallOutcome.NEEDS_USER
        }
        installManager.install(InstallItem(PackageName(packageName), cacheFileName))
        // Bounded, unlike the screen's own wait: that one is tied to the screen's lifetime and simply
        // dies with it, while here nothing would ever cancel it. An install method that asks the user to
        // confirm leaves this pending for as long as the notification goes unanswered, which on a worker
        // with a fixed execution budget would spend the whole batch's time on one app. Giving up only
        // costs the record write below; the next update check reads the device's real version anyway and
        // reconciles it (see ExternalApp.hasUpdateGiven).
        val recorded = withTimeoutOrNull(INSTALL_CONFIRM_TIMEOUT_MS) {
            awaitAndRecordInstall(
                key = app.key,
                packageName = packageName,
                tag = app.latestTag,
                token = app.latestApkToken,
            )
        }
        if (recorded == null) {
            Log.i(TAG, "Install of ${app.key} not confirmed in time, moving on")
        }
        return ExternalInstallOutcome.STARTED
    }

    /**
     * Waits for the system to actually finish installing [packageName], then records on the source
     * [key] which release now sits on the device.
     *
     * Everything here is written only once the install is confirmed, never right after enqueueing it.
     * Writing optimistically used to leave a stale "installed" record (and so a wrongly-hidden update,
     * or a permanently wrong [ExternalApp.packageName]) whenever an install silently failed after the
     * caller had already returned: a signature conflict, or a cancelled system install dialog.
     */
    suspend fun awaitAndRecordInstall(
        key: String,
        packageName: String,
        tag: String?,
        token: String?,
    ) {
        val terminal = installManager.state
            .map { it[PackageName(packageName)] }
            // Wait to actually see this install start (Pending/Installing) before accepting a terminal
            // value, otherwise a stale Installed/Failed already sitting in the map from an earlier,
            // unrelated attempt on the same package could be mistaken for this one's result the instant
            // this collector subscribes.
            .dropWhile { it != InstallState.Pending && it != InstallState.Installing }
            .first { it == InstallState.Installed || it == InstallState.Failed }
        if (terminal != InstallState.Installed) return
        val current = repository.getApps().firstOrNull { it.key == key } ?: return
        val versionName = refresher.installedVersionName(packageName)
        // What this install leaves on record. An update wrongly offered straight after one is these
        // three not lining up with the latest* half logged by the refresher, and the absence of this
        // line means the record was never written at all.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "installed $key: tag=$tag token=$token version=$versionName")
        }
        repository.upsertApp(
            current.copy(
                packageName = packageName,
                installedTag = tag,
                installedApkToken = token,
                installedVersionName = versionName,
            ),
        )
    }
}

private const val TAG = "ExternalInstaller"

/** How long an unattended install waits for the system to confirm before moving on to the next app.
 *  Ample for one that installs on its own; short enough that a batch isn't spent waiting on a
 *  confirmation dialog nobody is there to answer. */
private const val INSTALL_CONFIRM_TIMEOUT_MS = 90_000L

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

/** Cache file name for a source's release APK. One definition, so the foreground and background install
 *  paths address the same file instead of each downloading its own copy under a slightly different name. */
internal fun releaseCacheFileName(app: ExternalApp, tag: String?): String =
    "${app.provider.name}_${app.owner}_${app.repo}_${tag.orEmpty()}.apk".replace(UNSAFE_FILE_CHARS, "_")
