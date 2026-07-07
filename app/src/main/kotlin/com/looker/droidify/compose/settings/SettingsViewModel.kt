package com.looker.droidify.compose.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.SnackbarHostState
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.data.PrivacyRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.StringHandler
import com.looker.droidify.data.model.Authentication
import com.looker.droidify.data.model.Repo
import com.looker.droidify.database.RepositoryExporter
import com.looker.droidify.model.Repository
import com.looker.droidify.datastore.CustomButtonRepository
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.LegacyInstallerComponent
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.installer.installers.initSui
import com.looker.droidify.installer.installers.isMagiskGranted
import com.looker.droidify.installer.installers.isShizukuAlive
import com.looker.droidify.installer.installers.isShizukuGranted
import com.looker.droidify.installer.installers.isShizukuInstalled
import com.looker.droidify.installer.installers.requestPermissionListener
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.work.CleanUpWorker
import com.looker.droidify.work.DownloadStatsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val privacyRepository: PrivacyRepository,
    private val repositoryExporter: RepositoryExporter,
    private val repoRepository: RepoRepository,
    private val customButtonRepository: CustomButtonRepository,
    private val externalAppRepository: ExternalAppRepository,
    private val handler: StringHandler,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val snackbarHostState = SnackbarHostState()

    val settings = settingsRepository.data.asStateFlow(Settings())

    val customButtons: StateFlow<List<CustomButton>> = customButtonRepository.buttons
        .asStateFlow(emptyList())

    private val _isBackgroundAllowed = MutableStateFlow(true)
    val isBackgroundAllowed = _isBackgroundAllowed.asStateFlow()

    fun updateBackgroundAccessState(allowed: Boolean) {
        _isBackgroundAllowed.value = allowed
    }

    fun showSnackbar(@StringRes messageRes: Int) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(handler.getString(messageRes))
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            val appLocale = LocaleListCompat.create(language.toLocale())
            AppCompatDelegate.setApplicationLocales(appLocale)
            settingsRepository.setLanguage(language)
        }
    }

    fun setTheme(theme: Theme) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
        }
    }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicTheme(enabled)
        }
    }

    fun setThemeColor(color: Int) {
        viewModelScope.launch {
            settingsRepository.setThemeColor(color)
        }
    }

    fun setEdgeToEdge(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEdgeToEdge(enabled)
        }
    }

    fun setHomeScreenSwiping(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHomeScreenSwiping(enabled)
        }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdate(enabled)
        }
    }

    fun setNotifyUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.enableNotifyUpdates(enabled)
        }
    }

    fun setUnstableUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.enableUnstableUpdates(enabled)
        }
    }

    fun setIgnoreSignature(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIgnoreSignature(enabled)
        }
    }

    fun setIncompatibleUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.enableIncompatibleVersion(enabled)
        }
    }

    fun setAutoSync(autoSync: AutoSync) {
        viewModelScope.launch {
            settingsRepository.setAutoSync(autoSync)
        }
    }

    fun setCleanUpInterval(interval: Duration) {
        viewModelScope.launch {
            settingsRepository.setCleanUpInterval(interval)
        }
    }

    fun forceCleanup(context: Context) {
        viewModelScope.launch {
            CleanUpWorker.force(context)
        }
    }

    fun setInstaller(context: Context, installerType: InstallerType) {
        viewModelScope.launch {
            when (installerType) {
                InstallerType.SHIZUKU -> handleShizukuInstaller(context, installerType)
                InstallerType.ROOT -> handleRootInstaller(installerType)
                InstallerType.LEGACY -> {
                    settingsRepository.setDeleteApkOnInstall(false)
                    settingsRepository.setInstallerType(installerType)
                }
                else -> settingsRepository.setInstallerType(installerType)
            }
        }
    }

    private suspend fun handleShizukuInstaller(context: Context, installerType: InstallerType) {
        if (isShizukuInstalled(context) || initSui(context)) {
            when {
                !isShizukuAlive() -> showSnackbar(R.string.shizuku_not_alive)
                isShizukuGranted() -> settingsRepository.setInstallerType(installerType)
                else -> {
                    if (requestPermissionListener()) {
                        settingsRepository.setInstallerType(installerType)
                    }
                }
            }
        } else {
            showSnackbar(R.string.shizuku_not_installed)
        }
    }

    private suspend fun handleRootInstaller(installerType: InstallerType) {
        if (isMagiskGranted()) {
            settingsRepository.setInstallerType(installerType)
        }
    }

    fun setLegacyInstallerComponent(component: LegacyInstallerComponent?) {
        viewModelScope.launch {
            settingsRepository.setLegacyInstallerComponent(component)
        }
    }

    fun setDeleteApkOnInstall(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDeleteApkOnInstall(enabled)
        }
    }

    fun setDownloadStatisticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDownloadStatisticsEnabled(enabled)
            if (enabled) {
                // Fetch now so the "Most downloaded" carousel fills in, and keep it fresh afterwards.
                DownloadStatsWorker.fetchDownloadStats(context)
                DownloadStatsWorker.schedulePeriodic(context)
            } else {
                DownloadStatsWorker.cancelPeriodic(context)
                privacyRepository.clearDownloadStats()
            }
        }
    }

    fun setReproducibilityLogsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRBLogsEnabled(enabled)
            if (!enabled) {
                privacyRepository.clearRbLogs()
            }
        }
    }

    fun setProxyType(proxyType: ProxyType) {
        viewModelScope.launch {
            settingsRepository.setProxyType(proxyType)
            showSnackbar(R.string.proxy_restart_required)
        }
    }

    fun setProxyHost(host: String) {
        viewModelScope.launch {
            settingsRepository.setProxyHost(host)
            showSnackbar(R.string.proxy_restart_required)
        }
    }

    fun setProxyPort(port: String) {
        viewModelScope.launch {
            val portInt = port.toIntOrNull()
            if (portInt == null) {
                showSnackbar(R.string.proxy_port_error_not_int)
            } else {
                settingsRepository.setProxyPort(portInt)
                showSnackbar(R.string.proxy_restart_required)
            }
        }
    }

    fun setGithubToken(token: String) {
        viewModelScope.launch {
            settingsRepository.setGithubToken(token.trim())
        }
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        viewModelScope.launch {
            settingsRepository.setTranslationEngine(engine)
        }
    }

    fun setLibreTranslateUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setLibreTranslateUrl(url.trim())
        }
    }

    fun setLibreTranslateApiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setLibreTranslateApiKey(key.trim())
        }
    }

    fun setAutoTranslate(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoTranslate(enabled)
        }
    }

    fun setReadmeJavaScriptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setReadmeJavaScriptEnabled(enabled)
        }
    }

    fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.export(uri)
        }
    }

    fun importSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                settingsRepository.import(uri)
            } catch (e: Exception) {
                showSnackbar(R.string.file_format_error_DESC)
            }
        }
    }

    fun exportRepos(uri: Uri) {
        viewModelScope.launch {
            // getRepo() (unlike the repos flow) also resolves credentials and mirrors, so the
            // backup keeps private-repo logins.
            val repos = repoRepository.repos.first()
                .mapNotNull { repoRepository.getRepo(it.id) }
                .map { it.toExportRepository() }
            repositoryExporter.export(repos, uri)
        }
    }

    fun importRepos(uri: Uri) {
        viewModelScope.launch {
            val imported = try {
                repositoryExporter.import(uri)
            } catch (e: Exception) {
                showSnackbar(R.string.file_format_error_DESC)
                return@launch
            }
            // Skip repos we already have: RepoEntity has no unique address constraint, so a blind
            // insert would create duplicates.
            val existing = repoRepository.addresses.first()
                .map { it.normalizeRepoAddress() }
                .toSet()
            imported.forEach { repo ->
                if (repo.address.normalizeRepoAddress() in existing) return@forEach
                val (username, password) = repo.authentication.basicCredentials()
                repoRepository.insertRepo(
                    address = repo.address,
                    fingerprint = repo.fingerprint.ifEmpty { null },
                    username = username,
                    password = password,
                    name = repo.name.ifEmpty { null },
                    description = repo.description.ifEmpty { null },
                )
            }
            // Restore the backup's enabled state (enabling also triggers a sync). Re-query because
            // insertRepo doesn't return a usable Repo — same approach as the default-repo seeding.
            val enabledAddresses = imported
                .filter { it.enabled }
                .map { it.address.normalizeRepoAddress() }
                .toSet()
            repoRepository.repos.first()
                .filter { it.address.normalizeRepoAddress() in enabledAddresses && !it.enabled }
                .forEach { repoRepository.enableRepository(it, enable = true) }
        }
    }

    fun exportExternalSources(uri: Uri) {
        viewModelScope.launch {
            externalAppRepository.exportToUri(uri).onFailure {
                showSnackbar(R.string.file_format_error_DESC)
            }
        }
    }

    fun importExternalSources(uri: Uri) {
        viewModelScope.launch {
            externalAppRepository.importFromUri(uri).fold(
                onSuccess = { count -> if (count > 0) showSnackbar(R.string.external_imported) },
                onFailure = { showSnackbar(R.string.file_format_error_DESC) },
            )
        }
    }

    fun addCustomButton(button: CustomButton) {
        viewModelScope.launch {
            customButtonRepository.addButton(button)
        }
    }

    fun updateCustomButton(button: CustomButton) {
        viewModelScope.launch {
            customButtonRepository.updateButton(button)
        }
    }

    fun removeCustomButton(buttonId: String) {
        viewModelScope.launch {
            customButtonRepository.removeButton(buttonId)
        }
    }

    fun exportCustomButtons(uri: Uri) {
        viewModelScope.launch {
            customButtonRepository.exportToUri(uri).onFailure {
                showSnackbar(R.string.file_format_error_DESC)
            }
        }
    }

    fun importCustomButtons(uri: Uri) {
        viewModelScope.launch {
            customButtonRepository.importFromUri(uri).fold(
                onSuccess = { count ->
                    if (count > 0) {
                        showSnackbar(R.string.custom_buttons_imported)
                    }
                },
                onFailure = {
                    showSnackbar(R.string.file_format_error_DESC)
                },
            )
        }
    }

    companion object {
        val cleanUpIntervals: List<Duration> = listOf(
            6.hours,
            12.hours,
            18.hours,
            1.days,
            2.days,
            Duration.INFINITE,
        )

        val localeCodesList: List<String> = BuildConfig.DETECTED_LOCALES
            .toList()
            .updateAsMutable { add(0, "system") }
    }
}

private fun String.toLocale(): Locale = when {
    contains("-r") -> Locale(substring(0, 2), substring(4))
    contains("_") -> Locale(substring(0, 2), substring(3))
    else -> Locale(this)
}

/** Trailing-slash-insensitive form used to match repo addresses across backup and DB. */
private fun String.normalizeRepoAddress(): String = trimEnd('/')

/** Maps a Room [Repo] to the legacy [Repository] shape understood by [RepositoryExporter]. */
private fun Repo.toExportRepository(): Repository = Repository(
    id = id.toLong(),
    address = address,
    mirrors = mirrors,
    name = name,
    description = description.raw,
    // Legacy index-format version; unused on re-import, kept for backup-format compatibility.
    version = 21,
    enabled = enabled,
    fingerprint = fingerprint?.value ?: "",
    lastModified = "",
    entityTag = "",
    updated = 0L,
    timestamp = versionInfo?.timestamp ?: 0L,
    authentication = authentication?.toBasicAuth() ?: "",
)

/** Legacy "Basic <base64(user:pass)>" credential string (matches KtorHeadersBuilder). */
private fun Authentication.toBasicAuth(): String =
    "Basic " + Base64.encode("$username:$password".encodeToByteArray())

/** Splits a legacy "Basic <base64(user:pass)>" string back into (username, password). */
private fun String.basicCredentials(): Pair<String?, String?> {
    if (isBlank()) return null to null
    return try {
        val decoded = Base64.decode(removePrefix("Basic ").trim()).decodeToString()
        val separator = decoded.indexOf(':')
        if (separator < 0) null to null
        else decoded.substring(0, separator) to decoded.substring(separator + 1)
    } catch (e: IllegalArgumentException) {
        null to null
    }
}
