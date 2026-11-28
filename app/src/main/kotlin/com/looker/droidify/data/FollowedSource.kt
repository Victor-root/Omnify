package com.looker.droidify.data

import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one source an app's updates come from, chosen by the user on that app's page among everything
 * offering it: one of the catalogue's repositories, or a tracked external source of the same package.
 * No choice means automatic, the rule Omnify applied before this existed.
 */
sealed interface FollowedSource {
    data class Repository(val repoId: Int) : FollowedSource
    data class External(val key: String) : FollowedSource

    /** How it is kept in [com.looker.droidify.datastore.Settings.followedSources]. */
    val stored: String
        get() = when (this) {
            is Repository -> "$REPOSITORY_PREFIX$repoId"
            is External -> "$EXTERNAL_PREFIX$key"
        }

    companion object {
        private const val REPOSITORY_PREFIX = "repo:"
        private const val EXTERNAL_PREFIX = "external:"

        fun parse(stored: String): FollowedSource? = when {
            stored.startsWith(REPOSITORY_PREFIX) ->
                stored.removePrefix(REPOSITORY_PREFIX).toIntOrNull()?.let(::Repository)
            stored.startsWith(EXTERNAL_PREFIX) -> External(stored.removePrefix(EXTERNAL_PREFIX))
            else -> null
        }
    }
}

/**
 * The repository each app several repositories offer is shown as, by package name: the one followed for it
 * ([followed], as stored), else the one its installed copy came from ([installedKeyRepositories], signed
 * with its key). Any other app keeps whichever of its repositories the catalogue reads first.
 */
fun presentingRepositories(
    followed: Map<String, String>,
    installedKeyRepositories: Map<String, Int>,
): Map<String, Int> = installedKeyRepositories + followed.mapNotNull { (packageName, stored) ->
    (FollowedSource.parse(stored) as? FollowedSource.Repository)?.let { packageName to it.repoId }
}

/** [presentingRepositories] for one app, from its builds ([packages], every repository's). */
fun presentingRepository(
    followed: FollowedSource?,
    installedSigner: String?,
    packages: List<Pair<Package, Repo>>,
): Int? = (followed as? FollowedSource.Repository)?.repoId?.takeIf { id -> packages.any { it.second.id == id } }
    ?: packages.filter { signedWith(installedSigner, it.first.manifest.signer) }
        .maxByOrNull { it.first.manifest.versionCode }
        ?.second?.id

/**
 * The source the user follows for [packageName], as long as it can still be followed: a repository
 * still offering the app ([repoIds], the ones that do; a disabled repository's apps are gone from the
 * catalogue), or an external source still tracking the package and switched on. Null otherwise, which
 * is the automatic choice: a source the user turned off or removed must not leave the app without
 * updates, and the app's page says that it went back to automatic.
 */
fun followedSourceOf(
    stored: String?,
    packageName: String,
    repoIds: Collection<Int>,
    externalApps: List<ExternalApp>,
): FollowedSource? = stored?.let(FollowedSource::parse)?.takeIf { source ->
    when (source) {
        is FollowedSource.Repository -> source.repoId in repoIds
        is FollowedSource.External -> externalApps.any {
            it.key == source.key && it.packageName == packageName && it.enabled
        }
    }
}

/**
 * Whether [this] external source, tracking an installed package, takes that package's updates rather
 * than the catalogue: the one the user follows does and no other, and following a repository leaves
 * them all out. With no choice, [automatic] decides, the rule [externalSourceOwns] holds.
 */
fun ExternalApp.takesUpdates(followed: FollowedSource?, automatic: () -> Boolean): Boolean = when (followed) {
    is FollowedSource.External -> followed.key == key
    is FollowedSource.Repository -> false
    null -> automatic()
}

/** The sources followed across every app, worked out in one pass for the update checks. */
@Singleton
class FollowedSources @Inject constructor(
    private val appRepository: AppRepository,
    private val externalAppRepository: ExternalAppRepository,
    private val settingsRepository: SettingsRepository,
) {
    /** What the user chose, as stored, again whenever a choice changes. */
    val stored: Flow<Map<String, String>> = settingsRepository.get { followedSources }

    /** Every package's followed source that can still be followed (see [followedSourceOf]). */
    suspend fun current(): Map<String, FollowedSource> {
        val stored = stored.first()
        if (stored.isEmpty()) return emptyMap()
        val repoIds = appRepository.repoIdsOffering(stored.keys)
        val externalApps = externalAppRepository.getApps()
        return stored.mapNotNull { (packageName, value) ->
            followedSourceOf(value, packageName, repoIds[packageName].orEmpty(), externalApps)
                ?.let { packageName to it }
        }.toMap()
    }
}
