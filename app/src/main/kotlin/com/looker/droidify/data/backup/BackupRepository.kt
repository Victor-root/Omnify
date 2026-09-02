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
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.external.ExternalAppRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTRY_MANIFEST = "manifest.json"
private const val ENTRY_SETTINGS = "settings.json"
private const val ENTRY_GITHUB_TOKEN = "github_token.json"
private const val ENTRY_REPOSITORIES = "repositories.json"
private const val ENTRY_EXTERNAL_SOURCES = "external_sources.json"
private const val ENTRY_FAVOURITES = "favourites.json"
private const val ENTRY_HIDDEN_APPS = "hidden_apps.json"
private const val ENTRY_CUSTOM_BUTTONS = "custom_buttons.json"

/**
 * Ceilings on what [BackupRepository.readAllEntries] will hold in memory for one imported archive.
 * Both sit far above any real backup, whose largest entry is the external-sources list: a few
 * hundred tracked sources come to well under a megabyte of JSON. So the only thing these ever stop
 * is a file that was never a backup of this app's data.
 */
private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

private val CATEGORY_ENTRY_NAMES = mapOf(
    BackupCategory.SETTINGS to ENTRY_SETTINGS,
    BackupCategory.GITHUB_TOKEN to ENTRY_GITHUB_TOKEN,
    BackupCategory.REPOSITORIES to ENTRY_REPOSITORIES,
    BackupCategory.EXTERNAL_SOURCES to ENTRY_EXTERNAL_SOURCES,
    BackupCategory.FAVOURITES to ENTRY_FAVOURITES,
    BackupCategory.HIDDEN_APPS to ENTRY_HIDDEN_APPS,
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
    private val externalApi: ExternalApi,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _restoreInProgress = MutableStateFlow(false)

    /** True for the whole span of [restoreBackup], including a restored SETTINGS category's own theme
     *  fields taking effect. [MainComposeActivity][com.looker.droidify.compose.MainComposeActivity]
     *  recreates itself whenever the app's theme settings change (needed to reapply the activity's own
     *  XML theme, not just the Compose one). Watched here so that recreation waits for a restore in
     *  progress to fully finish, rather than tearing down whatever dialog is on screen mid-restore. */
    val restoreInProgress: StateFlow<Boolean> = _restoreInProgress.asStateFlow()

    /** Writes a zip containing exactly [categories] to [target]. */
    suspend fun createBackup(target: Uri, categories: Set<BackupCategory>): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val output = context.contentResolver.openOutputStream(target)
                    ?: error("Cannot open output stream")
                writeBackup(output, categories)
            }
        }

    /**
     * The same zip [createBackup] writes, as bytes rather than into a file, for a device-to-device
     * transfer (see [com.looker.droidify.transfer.TransferHost]) where the archive is encrypted and
     * sent instead of ever being stored. Holding it in memory is what keeps a backup carrying
     * repository passwords and a GitHub token from being written to disk purely to be read straight
     * back and deleted; it is comfortably small enough (see [MAX_TOTAL_BYTES]) for that to be the
     * simpler as well as the safer option.
     */
    suspend fun createBackupBytes(categories: Set<BackupCategory>): Result<ByteArray> =
        withContext(ioDispatcher) {
            runCatching {
                val output = ByteArrayOutputStream()
                writeBackup(output, categories)
                output.toByteArray()
            }
        }

    /** Shared by both entry points above so the archive is built exactly once in the codebase, and a
     *  file and a transfer can never drift into carrying different things. */
    private suspend fun writeBackup(output: OutputStream, categories: Set<BackupCategory>) {
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
                // Favourites/hidden apps/enabled-repo-ids are zeroed here on purpose: they're
                // their own categories (FAVOURITES, HIDDEN_APPS, and the per-repo `enabled`
                // field inside REPOSITORIES), never re-derived from this entry on restore. See
                // BackupCategory's own doc comment for why they're split out at all. The GitHub
                // token goes the same way, into GITHUB_TOKEN, so leaving that category
                // unchecked genuinely keeps the token out of the file, rather than writing it
                // here anyway where nothing would ever look for it.
                val settings = settingsRepository.getInitial().copy(
                    favouriteApps = emptySet(),
                    favouritedAt = emptyMap(),
                    hiddenApps = emptySet(),
                    enabledRepoIds = emptySet(),
                    githubToken = "",
                )
                zip.writeEntry(ENTRY_SETTINGS, json.encodeToString(settings))
            }
            if (BackupCategory.GITHUB_TOKEN in categories) {
                val token = GithubTokenBackup(settingsRepository.getInitial().githubToken)
                zip.writeEntry(ENTRY_GITHUB_TOKEN, json.encodeToString(token))
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
            if (BackupCategory.HIDDEN_APPS in categories) {
                val hiddenApps = HiddenAppsBackup(settingsRepository.getInitial().hiddenApps)
                zip.writeEntry(ENTRY_HIDDEN_APPS, json.encodeToString(hiddenApps))
            }
            if (BackupCategory.CUSTOM_BUTTONS in categories) {
                val buttons = CustomButtonsBackup(customButtonRepository.getButtons())
                zip.writeEntry(ENTRY_CUSTOM_BUTTONS, json.encodeToString(buttons))
            }
        }
    }

    /** Reads [source]'s manifest and every entry's raw text, without applying anything — lets the
     *  restore dialog show a checkbox only for what this specific archive genuinely contains. */
    suspend fun inspectBackup(source: Uri): Result<BackupInspection> = withContext(ioDispatcher) {
        runCatching {
            val input = context.contentResolver.openInputStream(source) ?: error("Cannot open input stream")
            inspectEntries(readAllEntries(input))
        }
    }

    /**
     * The same inspection [inspectBackup] performs, on an archive that arrived over the network from
     * another device rather than out of a file the user picked.
     *
     * Deliberately the same path, bounds and all. A transfer is authenticated (only a device that was
     * given the pairing code can take part at all), but authenticated is not well-formed: the other
     * end is another install of this app, of some version, holding whatever its own data has become.
     * So what arrives is read exactly as cautiously as a file off a cloud drive, and reading it is all
     * this does: what it turned out to contain is shown before any of it is applied.
     */
    suspend fun inspectBackupBytes(bytes: ByteArray): Result<BackupInspection> = withContext(ioDispatcher) {
        runCatching { inspectEntries(readAllEntries(ByteArrayInputStream(bytes))) }
    }

    private fun inspectEntries(entries: Map<String, String>): BackupInspection {
        val manifestText = entries[ENTRY_MANIFEST] ?: error("Missing manifest.json — not an Omnify backup")
        val manifest = json.decodeFromString<BackupManifest>(manifestText)
        val available = manifest.categories.filterTo(mutableSetOf()) { category ->
            CATEGORY_ENTRY_NAMES[category] in entries
        }
        return BackupInspection(manifest, available, entries)
    }

    /** Applies exactly [categories] from [inspection] — each one only if it's also in
     *  [BackupInspection.availableCategories], so a category the caller asks for but the archive
     *  doesn't actually have is silently skipped rather than crashing. */
    suspend fun restoreBackup(inspection: BackupInspection, categories: Set<BackupCategory>): Result<Unit> =
        withContext(ioDispatcher) {
            _restoreInProgress.value = true
            try {
                restoreBackupInternal(inspection, categories)
            } finally {
                _restoreInProgress.value = false
            }
        }

    private suspend fun restoreBackupInternal(
        inspection: BackupInspection,
        categories: Set<BackupCategory>,
    ): Result<Unit> =
        runCatching {
            val toRestore = categories intersect inspection.availableCategories
            if (BackupCategory.SETTINGS in toRestore) {
                restoreSettings(json.decodeFromString(inspection.entries.getValue(ENTRY_SETTINGS)))
            }
            if (BackupCategory.GITHUB_TOKEN in toRestore) {
                val backup = json.decodeFromString<GithubTokenBackup>(
                    inspection.entries.getValue(ENTRY_GITHUB_TOKEN),
                )
                restoreGithubToken(backup.token)
            }
            if (BackupCategory.FAVOURITES in toRestore) {
                val backup = json.decodeFromString<FavouritesBackup>(inspection.entries.getValue(ENTRY_FAVOURITES))
                restoreFavourites(backup.packageNames)
            }
            if (BackupCategory.HIDDEN_APPS in toRestore) {
                val backup =
                    json.decodeFromString<HiddenAppsBackup>(inspection.entries.getValue(ENTRY_HIDDEN_APPS))
                restoreHiddenApps(backup.packageNames)
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

    private suspend fun restoreSettings(imported: Settings) {
        val current = settingsRepository.getInitial()
        settingsRepository.applySettings(
            imported.copy(
                favouriteApps = current.favouriteApps,
                favouritedAt = current.favouritedAt,
                hiddenApps = current.hiddenApps,
                enabledRepoIds = current.enabledRepoIds,
                // The token is BackupCategory.GITHUB_TOKEN's business, so the one in place stays put.
                // This also covers a backup written before that category existed: its settings entry
                // still carries a token, and restoring settings is not the user ticking the box that
                // says to take it.
                githubToken = current.githubToken,
            ),
        )
    }

    /**
     * Writes a restored GitHub token, then confirms it against GitHub. That check matters here because
     * nothing else on this path performs it: a restored token (however stale, expired, or unchanged
     * from before) has never actually been used yet, and without this the Settings screen would keep
     * showing whatever verification state was sitting in memory before the restore (most likely "never
     * checked yet", which reads as verified) until some unrelated background call organically noticed
     * a failure, possibly much later.
     */
    private suspend fun restoreGithubToken(token: String) {
        settingsRepository.setGithubToken(token)
        externalApi.verifyGithubToken()
    }

    private suspend fun restoreFavourites(packageNames: Set<String>) {
        val current = settingsRepository.getInitial().favouriteApps
        // toggleFavourites() adds when absent, removes when present — only call it for names genuinely
        // missing from the current set, so an already-favourited app is never accidentally un-favourited.
        (packageNames - current).forEach { settingsRepository.toggleFavourites(it) }
    }

    private suspend fun restoreHiddenApps(packageNames: Set<String>) {
        val current = settingsRepository.getInitial().hiddenApps
        // Same reasoning as restoreFavourites(): only toggle names genuinely missing from the current
        // set, so an already-hidden app is never accidentally unhidden.
        (packageNames - current).forEach { settingsRepository.toggleHidden(it) }
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
        // Enabled by address rather than by the ids just inserted: the loop above skips the repositories
        // already present, and those have to be switched on too when the backup says they were. Same
        // approach the default-repo seeding at first run already uses.
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

    /**
     * The text of the entries this app knows how to restore, read once from [input].
     *
     * A backup is a file the user picked, and a file the user picked is not necessarily a file this
     * app wrote: it travels (a chat, a mail attachment, a cloud drive) and can just as easily be one
     * someone else made. So the read is bounded rather than trusting what the archive declares about
     * itself. Entries this app doesn't restore are skipped instead of being held in memory, an entry
     * is refused past [MAX_ENTRY_BYTES] and the archive as a whole past [MAX_TOTAL_BYTES]: a zip
     * whose entries decompress to far more than their stored size (the shape of the whole file is a
     * few hundred bytes) would otherwise be read to the end and take the app down with it. The
     * decompressed size is measured as it is read, never taken from [ZipEntry.getSize], which is a
     * claim made by the archive. All of which applies just as much to one that arrived from another
     * device (see [inspectBackupBytes]) as to one off the filesystem.
     */
    private fun readAllEntries(input: InputStream): Map<String, String> {
        val known = CATEGORY_ENTRY_NAMES.values.toSet() + ENTRY_MANIFEST
        val entries = mutableMapOf<String, String>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in known && entry.name !in entries) {
                    val bytes = zip.readAtMost(MAX_ENTRY_BYTES)
                        ?: error("${entry.name} is larger than a backup entry can be")
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) error("Backup is larger than a backup can be")
                    entries[entry.name] = bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    /** The current entry's bytes, or null as soon as it turns out to hold more than [limit] of them,
     *  without ever buffering past that. An entry of exactly [limit] bytes still comes back whole. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > limit) return null
            output.write(buffer, 0, read)
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Trailing-slash-insensitive form used to match repo addresses across a backup and the database. */
    private fun String.normalizeRepoAddress(): String = trimEnd('/')
}
