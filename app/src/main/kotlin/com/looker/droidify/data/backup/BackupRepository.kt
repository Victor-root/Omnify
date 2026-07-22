package com.looker.droidify.data.backup

import android.content.Context
import android.net.Uri
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.datastore.CustomButtonRepository
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.di.IoDispatcher
import com.looker.droidify.external.ExternalAppRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTRY_MANIFEST = "manifest.json"
private const val ENTRY_SETTINGS = "settings.json"
private const val ENTRY_REPOSITORIES = "repositories.json"
private const val ENTRY_EXTERNAL_SOURCES = "external_sources.json"
private const val ENTRY_FAVOURITES = "favourites.json"
private const val ENTRY_CUSTOM_BUTTONS = "custom_buttons.json"

private val CATEGORY_ENTRY_NAMES = mapOf(
    BackupCategory.SETTINGS to ENTRY_SETTINGS,
    BackupCategory.REPOSITORIES to ENTRY_REPOSITORIES,
    BackupCategory.EXTERNAL_SOURCES to ENTRY_EXTERNAL_SOURCES,
    BackupCategory.FAVOURITES to ENTRY_FAVOURITES,
    BackupCategory.CUSTOM_BUTTONS to ENTRY_CUSTOM_BUTTONS,
)

/**
 * Everything a backup zip contains, parsed and held in memory: [manifest] as written by whoever created
 * it, [availableCategories] cross-checked against which entries the archive genuinely holds (a
 * corrupted/hand-edited zip can claim a category in its manifest without the file being there), and the
 * raw JSON text of every entry, keyed by file name. [restoreBackup] takes this directly rather than a
 * fresh [Uri] so the file is only ever read once per backup, and so the restore dialog's checkbox list
 * (built from [availableCategories]) always matches exactly what [restoreBackup] can act on.
 */
data class BackupInspection(
    val manifest: BackupManifest,
    val availableCategories: Set<BackupCategory>,
    internal val entries: Map<String, String>,
)

/**
 * The single place that turns the app's scattered persistence (DataStore settings, the repo database,
 * the external-sources JSON files, the custom-buttons JSON file) into one zip, and back. Every category
 * is independently selectable on both ends — creating a backup writes only the entries the caller asked
 * for; restoring only ever touches the categories the caller both asked for AND that are actually
 * present in the archive (see [BackupInspection]).
 *
 * Existing data is never wiped by a restore, only added to: repositories/external sources/custom
 * buttons already present (matched by address/key/id) are left untouched, favourites are unioned, and
 * settings fields are applied on top of whatever's already set — the same non-destructive behaviour the
 * four separate import flows this replaces already had, now guaranteed to behave identically since
 * there's exactly one implementation of each merge instead of four independent copies.
 */
@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val repoRepository: RepoRepository,
    private val externalAppRepository: ExternalAppRepository,
    private val customButtonRepository: CustomButtonRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Writes a zip containing exactly [categories] to [target]. */
    suspend fun createBackup(target: Uri, categories: Set<BackupCategory>): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val output = context.contentResolver.openOutputStream(target)
                    ?: error("Cannot open output stream")
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    zip.writeEntry(
                        ENTRY_MANIFEST,
                        json.encodeToString(
                            BackupManifest(
                                appVersionName = BuildConfig.VERSION_NAME,
                                exportedAt = System.currentTimeMillis(),
                                categories = categories,
                            ),
                        ),
                    )
                    if (BackupCategory.SETTINGS in categories) {
                        // Favourites/enabled-repo-ids are zeroed here on purpose — they're their own
                        // categories (FAVOURITES, and the per-repo `enabled` field inside
                        // REPOSITORIES), never re-derived from this entry on restore. See
                        // BackupCategory's own doc comment for why they're split out at all.
                        val settings = settingsRepository.getInitial()
                            .copy(favouriteApps = emptySet(), enabledRepoIds = emptySet())
                        zip.writeEntry(ENTRY_SETTINGS, json.encodeToString(settings))
                    }
                    if (BackupCategory.REPOSITORIES in categories) {
                        // getRepo() (unlike the repos flow) also resolves credentials and mirrors — the
                        // flow alone would silently drop saved logins from the backup.
                        val repos = repoRepository.repos.first()
                            .mapNotNull { repoRepository.getRepo(it.id) }
                            .map {
                                RepoBackupEntry(
                                    address = it.address,
                                    name = it.name,
                                    description = it.description.raw,
                                    fingerprint = it.fingerprint?.value.orEmpty(),
                                    enabled = it.enabled,
                                    username = it.authentication?.username,
                                    password = it.authentication?.password,
                                )
                            }
                        zip.writeEntry(ENTRY_REPOSITORIES, json.encodeToString(RepositoriesBackup(repos)))
                    }
                    if (BackupCategory.EXTERNAL_SOURCES in categories) {
                        val bundle = ExternalSourcesBackup(
                            apps = externalAppRepository.getApps(),
                            accounts = externalAppRepository.getAccounts(),
                        )
                        zip.writeEntry(ENTRY_EXTERNAL_SOURCES, json.encodeToString(bundle))
                    }
                    if (BackupCategory.FAVOURITES in categories) {
                        val favourites = FavouritesBackup(settingsRepository.getInitial().favouriteApps)
                        zip.writeEntry(ENTRY_FAVOURITES, json.encodeToString(favourites))
                    }
                    if (BackupCategory.CUSTOM_BUTTONS in categories) {
                        val buttons = CustomButtonsBackup(customButtonRepository.getButtons())
                        zip.writeEntry(ENTRY_CUSTOM_BUTTONS, json.encodeToString(buttons))
                    }
                }
            }
        }

    /** Reads [source]'s manifest and every entry's raw text, without applying anything — lets the
     *  restore dialog show a checkbox only for what this specific archive genuinely contains. */
    suspend fun inspectBackup(source: Uri): Result<BackupInspection> = withContext(ioDispatcher) {
        runCatching {
            val entries = readAllEntries(source)
            val manifestText = entries[ENTRY_MANIFEST] ?: error("Missing manifest.json — not an Omnify backup")
            val manifest = json.decodeFromString<BackupManifest>(manifestText)
            val available = manifest.categories.filterTo(mutableSetOf()) { category ->
                CATEGORY_ENTRY_NAMES[category] in entries
            }
            BackupInspection(manifest, available, entries)
        }
    }

    /** Applies exactly [categories] from [inspection] — each one only if it's also in
     *  [BackupInspection.availableCategories], so a category the caller asks for but the archive
     *  doesn't actually have is silently skipped rather than crashing. */
    suspend fun restoreBackup(inspection: BackupInspection, categories: Set<BackupCategory>): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val toRestore = categories intersect inspection.availableCategories
                if (BackupCategory.SETTINGS in toRestore) {
                    restoreSettings(json.decodeFromString(inspection.entries.getValue(ENTRY_SETTINGS)))
                }
                if (BackupCategory.FAVOURITES in toRestore) {
                    val backup = json.decodeFromString<FavouritesBackup>(inspection.entries.getValue(ENTRY_FAVOURITES))
                    restoreFavourites(backup.packageNames)
                }
                if (BackupCategory.REPOSITORIES in toRestore) {
                    val backup =
                        json.decodeFromString<RepositoriesBackup>(inspection.entries.getValue(ENTRY_REPOSITORIES))
                    restoreRepositories(backup.repositories)
                }
                if (BackupCategory.EXTERNAL_SOURCES in toRestore) {
                    val backup = json.decodeFromString<ExternalSourcesBackup>(
                        inspection.entries.getValue(ENTRY_EXTERNAL_SOURCES),
                    )
                    restoreExternalSources(backup)
                }
                if (BackupCategory.CUSTOM_BUTTONS in toRestore) {
                    val backup = json.decodeFromString<CustomButtonsBackup>(
                        inspection.entries.getValue(ENTRY_CUSTOM_BUTTONS),
                    )
                    restoreCustomButtons(backup.buttons)
                }
            }
        }

    private suspend fun restoreSettings(imported: Settings) {
        val current = settingsRepository.getInitial()
        settingsRepository.applySettings(
            imported.copy(
                favouriteApps = current.favouriteApps,
                enabledRepoIds = current.enabledRepoIds,
            ),
        )
    }

    private suspend fun restoreFavourites(packageNames: Set<String>) {
        val current = settingsRepository.getInitial().favouriteApps
        // toggleFavourites() adds when absent, removes when present — only call it for names genuinely
        // missing from the current set, so an already-favourited app is never accidentally un-favourited.
        (packageNames - current).forEach { settingsRepository.toggleFavourites(it) }
    }

    private suspend fun restoreRepositories(imported: List<RepoBackupEntry>) {
        val existing = repoRepository.addresses.first().map { it.normalizeRepoAddress() }.toSet()
        imported.forEach { repo ->
            if (repo.address.normalizeRepoAddress() in existing) return@forEach
            repoRepository.insertRepo(
                address = repo.address,
                fingerprint = repo.fingerprint.ifEmpty { null },
                username = repo.username,
                password = repo.password,
                name = repo.name.ifEmpty { null },
                description = repo.description.ifEmpty { null },
            )
        }
        // Re-query and enable by address (insertRepo doesn't return a usable id) — same approach the
        // default-repo seeding at first run already uses.
        val enabledAddresses = imported.filter { it.enabled }.map { it.address.normalizeRepoAddress() }.toSet()
        repoRepository.repos.first()
            .filter { it.address.normalizeRepoAddress() in enabledAddresses && !it.enabled }
            .forEach { repoRepository.enableRepository(it, enable = true) }
    }

    private suspend fun restoreExternalSources(backup: ExternalSourcesBackup) {
        val existingAppKeys = externalAppRepository.getApps().mapTo(mutableSetOf()) { it.key }
        externalAppRepository.upsertApps(backup.apps.filter { it.key !in existingAppKeys })
        val existingAccountKeys = externalAppRepository.getAccounts().mapTo(mutableSetOf()) { it.key }
        backup.accounts
            .filter { it.key !in existingAccountKeys }
            .forEach { externalAppRepository.upsertAccount(it) }
    }

    private suspend fun restoreCustomButtons(imported: List<CustomButton>) {
        val existingIds = customButtonRepository.getButtons().mapTo(mutableSetOf()) { it.id }
        imported.filter { it.id !in existingIds }.forEach { customButtonRepository.addButton(it) }
    }

    private fun readAllEntries(source: Uri): Map<String, String> {
        val input = context.contentResolver.openInputStream(source) ?: error("Cannot open input stream")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Trailing-slash-insensitive form used to match repo addresses across a backup and the database. */
    private fun String.normalizeRepoAddress(): String = trimEnd('/')
}
