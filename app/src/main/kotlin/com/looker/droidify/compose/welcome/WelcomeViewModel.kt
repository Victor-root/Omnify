package com.looker.droidify.compose.welcome

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.compose.repoList.defaultRepoName
import com.looker.droidify.compose.settings.RestoreSession
import com.looker.droidify.data.backup.BackupRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Repo
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.chooseAccentColor
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.BackgroundStyle
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.installer.InstallerSelection
import com.looker.droidify.migration.ChannelMigration
import com.looker.droidify.migration.ImportStep
import com.looker.droidify.model.Repository
import com.looker.droidify.utility.common.device.isTelevision
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A source as the welcome page shows it, with what it takes to draw its logo and description. */
data class WelcomeItem(
    val id: String,
    val name: String,
    val checked: Boolean,
    val kind: WelcomeKind,
)

/** The look of the app as the welcome page's appearance step shows it. */
data class Appearance(
    val theme: Theme,
    val themeColor: Int,
    val dynamicTheme: Boolean,
    val backgroundStyle: BackgroundStyle,
)

private fun Settings.toAppearance() = Appearance(theme, themeColor, dynamicTheme, backgroundStyle)

sealed interface WelcomeKind {
    /** Omnify's own update channel, an external source shown with the app's own icon. */
    data object OmnifySource : WelcomeKind

    /** The developer's whole GitHub account, an external account shown with the app's own icon. */
    data object OmnifyAccount : WelcomeKind

    /** An F-Droid repository. [description] is the line written for this page, or null to show the
     *  description the repository gives itself. */
    data class FdroidRepository(
        val repo: Repo,
        @param:StringRes val description: Int?,
    ) : WelcomeKind

    /** One of the apps made for television, offered on a television only. */
    data class TvApp(val app: ExternalApp) : WelcomeKind
}

/**
 * What the welcome pages of a fresh install need: the installer in use, for the first page, the ways to
 * bring data over, for the second, the look of the app, for the third, the sources with their ticks, for
 * the fourth, and the one action that ends it all by switching on whatever is
 * still ticked, which is also what starts the first synchronisation.
 *
 * A phone is offered every repository Omnify ships, plus Omnify's own update channel and its
 * developer's account. A television is offered the main repositories and the apps made for it, which
 * are what that screen is for.
 *
 * The ticks live in the saved state: giving Omnify the permission to install apps can make Android
 * close it (see welcome_install_hint), and the person coming back should find their choices as they
 * left them.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repoRepository: RepoRepository,
    private val externalAppRepository: ExternalAppRepository,
    private val settingsRepository: SettingsRepository,
    private val installerSelection: InstallerSelection,
    private val backupRepository: BackupRepository,
    private val channelMigration: ChannelMigration,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val isTelevision = context.isTelevision()

    private val checkedIds: StateFlow<ArrayList<String>> =
        savedStateHandle.getStateFlow(KEY_CHECKED, WelcomeCatalog.defaultChecked())

    /** The sources that exist yet: they are seeded on the first start, in parallel with this page
     *  opening, so a source simply appears in the list the moment it is there. */
    val items: StateFlow<List<WelcomeItem>> = combine(
        repoRepository.repos,
        externalAppRepository.apps,
        externalAppRepository.accounts,
        checkedIds,
    ) { repos, externalApps, accounts, checked ->
        val reposByAddress = repos.associateBy { it.address.trimEnd('/') }
        val offered = if (isTelevision) {
            WelcomeCatalog.describedRepositories.map { it.address }
        } else {
            WelcomeCatalog.repositoryOrder
        }
        // Then whatever a restore brought that the page does not offer of itself: a repository the
        // person had added, or one Omnify ships that the backup had on. Left out, it stayed invisible
        // here, and on for good whatever was ticked.
        val restored = repos.map { it.address.trimEnd('/') }
            .filter { it !in offered }
            .filter { it !in Repository.defaultAddresses || reposByAddress.getValue(it).enabled }
        val addresses = offered + restored
        buildList<WelcomeItem> {
            externalApps.firstOrNull { it.key == ExternalApp.OMNIFY_REPO_KEY }?.let {
                add(WelcomeItem(it.key, it.label, it.key in checked, WelcomeKind.OmnifySource))
            }
            if (!isTelevision) {
                accounts.firstOrNull { it.key == ExternalAccount.OMNIFY_KEY }?.let {
                    add(WelcomeItem(it.key, it.label, it.key in checked, WelcomeKind.OmnifyAccount))
                }
            }
            addresses.forEach { address ->
                val repo = reposByAddress[address] ?: return@forEach
                val kind = WelcomeKind.FdroidRepository(repo, WelcomeCatalog.description(address))
                add(WelcomeItem(address, defaultRepoName(repo.address) ?: repo.name, address in checked, kind))
            }
            if (isTelevision) {
                externalApps.filter { it.curatedTv }
                    .sortedBy { it.label.trim().lowercase() }
                    .forEach { add(WelcomeItem(it.key, it.label, it.key in checked, WelcomeKind.TvApp(it))) }
            }
        }
    }.flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** The installer in use, which the first page lets the person choose, as Settings does. */
    val installerType: StateFlow<InstallerType> =
        settingsRepository.get { installerType }.asStateFlow(InstallerType.Default)

    fun setInstaller(installerType: InstallerType) {
        viewModelScope.launch { installerSelection.select(installerType) }
    }

    /** The look in use, which the appearance page lets the person choose, as Settings does. */
    val appearance: StateFlow<Appearance> = settingsRepository
        .get { toAppearance() }
        .asStateFlow(Settings().toAppearance())

    fun setTheme(theme: Theme) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setBackgroundStyle(style: BackgroundStyle) {
        viewModelScope.launch { settingsRepository.setBackgroundStyle(style) }
    }

    fun chooseAccentColor(color: Int) {
        viewModelScope.launch { settingsRepository.chooseAccentColor(color) }
    }

    fun useWallpaperAccent() {
        viewModelScope.launch { settingsRepository.setDynamicTheme(true) }
    }

    /** The restore of a backup file, whose dialog the restore page shows. */
    val restore = RestoreSession(backupRepository, viewModelScope) {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
    }

    private val _fileRestored = MutableStateFlow(false)

    /** True once a backup file has been restored on this page. */
    val fileRestored: StateFlow<Boolean> = _fileRestored

    fun onFileRestored() {
        _fileRestored.value = true
    }

    private val _deviceRestored = MutableStateFlow(false)

    /** True once data received from another device has been applied on this page. */
    val deviceRestored: StateFlow<Boolean> = _deviceRestored

    fun onDeviceRestored() {
        _deviceRestored.value = true
    }

    private val _betaImport = MutableStateFlow(if (channelMigration.canImportFromBeta()) ImportStep.OFFERED else null)

    /** Where the import of an Omnify Beta left on the device stands, or null when there is none to offer. */
    val betaImport: StateFlow<ImportStep?> = _betaImport

    fun importFromBeta() {
        val step = _betaImport.value
        if (step != ImportStep.OFFERED && step != ImportStep.FAILED) return
        _betaImport.value = ImportStep.RUNNING
        viewModelScope.launch {
            val imported = channelMigration.importFromBeta().isSuccess
            // Answered here, so the prompt that would offer it again on the home stays away.
            if (imported) channelMigration.setDismissed()
            _betaImport.value = if (imported) ImportStep.IMPORTED else ImportStep.FAILED
        }
    }

    fun uninstallBeta() = channelMigration.uninstallBeta()

    init {
        // Repositories start out off, so one that is on was switched on by a restore, from whichever of
        // the ways to bring data over. The sources page then shows it ticked, and only what the backup
        // had on: the ticks offered by default would otherwise undo a backup that left F-Droid off.
        viewModelScope.launch {
            repoRepository.repos
                .map { repos ->
                    val addresses = repos.map { it.address.trimEnd('/') }
                    addresses to addresses.filterIndexed { index, _ -> repos[index].enabled }
                }
                .distinctUntilChanged()
                .collect { (all, enabled) -> if (enabled.isNotEmpty()) adoptEnabledRepositories(all.toSet(), enabled) }
        }
    }

    /** Ticks exactly the repositories [enabled] among [all] of them, the other ticks (Omnify's own
     *  sources, the TV apps) left as they are. */
    private fun adoptEnabledRepositories(all: Set<String>, enabled: List<String>) {
        setChecked(checkedIds.value.filter { it !in all } + enabled)
    }

    private val _finishing = MutableStateFlow(false)

    /** True from the moment the page is being ended, so a second tap cannot end it twice. */
    val finishing: StateFlow<Boolean> = _finishing

    fun toggle(id: String) {
        val current = checkedIds.value
        setChecked(if (id in current) current - id else current + id)
    }

    fun setAll(checked: Boolean) {
        setChecked(if (checked) items.value.map { it.id } else emptyList())
    }

    /**
     * Switches on what is ticked (each repository's own synchronisation starts with it), records that
     * the page has been seen, then calls [onDone]. Nothing ticked is a normal way out: nothing is
     * switched on and nothing is synchronised.
     */
    fun finish(onDone: () -> Unit) {
        if (!_finishing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val checked = checkedIds.value
                val reposByAddress = repoRepository.repos.first().associateBy { it.address.trimEnd('/') }
                // Every repository the page shows ends as ticked, off as well as on: one a restore had
                // switched on otherwise stayed on, unticked or not.
                items.value.filter { it.kind is WelcomeKind.FdroidRepository }
                    .mapNotNull { item -> reposByAddress[item.id]?.let { it to (item.id in checked) } }
                    .forEach { (repo, wanted) ->
                        if (repo.enabled != wanted) repoRepository.enableRepository(repo, enable = wanted)
                    }
                externalAppRepository.getApps()
                    .filter { it.key == ExternalApp.OMNIFY_REPO_KEY || it.curatedTv }
                    .forEach { app ->
                        val wanted = app.key in checked
                        if (app.enabled != wanted) externalAppRepository.upsertApp(app.copy(enabled = wanted))
                    }
                externalAppRepository.getAccounts()
                    .firstOrNull { it.key == ExternalAccount.OMNIFY_KEY }
                    ?.let { account ->
                        val wanted = account.key in checked
                        if (account.enabled != wanted) {
                            externalAppRepository.upsertAccount(account.copy(enabled = wanted))
                            externalAppRepository.setAccountAppsEnabled(account.key, wanted)
                        }
                    }
                FirstRun.finishWelcome(context)
                onDone()
            } finally {
                _finishing.value = false
            }
        }
    }

    private fun setChecked(ids: List<String>) {
        savedStateHandle[KEY_CHECKED] = ArrayList(ids)
    }

    private companion object {
        const val KEY_CHECKED = "checked"
    }
}
