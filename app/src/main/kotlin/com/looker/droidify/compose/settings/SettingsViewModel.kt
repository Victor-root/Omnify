package com.looker.droidify.compose.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.data.PrivacyRepository
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.data.backup.BackupRepository
import com.looker.droidify.datastore.CustomButtonRepository
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.chooseAccentColor
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.datastore.model.BackgroundStyle
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.LegacyInstallerComponent
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.installer.InstallerSelection
import com.looker.droidify.utility.common.SYSTEM_LANGUAGE
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.utility.common.localeOfCode
import com.looker.droidify.work.CleanUpWorker
import com.looker.droidify.work.DownloadStatsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val privacyRepository: PrivacyRepository,
    private val backupRepository: BackupRepository,
    private val customButtonRepository: CustomButtonRepository,
    private val externalApi: ExternalApi,
    private val installerSelection: InstallerSelection,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val settings = settingsRepository.data.asStateFlow(Settings())

    /** True while the configured GitHub token is being rejected outright — see
     *  [com.looker.droidify.external.ExternalApi.githubTokenInvalid]. Drives the warning shown right
     *  next to the token field below, where the user would actually fix it. */
    val githubTokenInvalid: StateFlow<Boolean> = externalApi.githubTokenInvalid

    val customButtons: StateFlow<List<CustomButton>> = customButtonRepository.buttons
        .asStateFlow(emptyList())

    private val _isBackgroundAllowed = MutableStateFlow(true)
    val isBackgroundAllowed = _isBackgroundAllowed.asStateFlow()

    /** The restore of a backup file, whose dialog the screen shows. */
    val restore = RestoreSession(backupRepository, viewModelScope, ::showToast)

    fun updateBackgroundAccessState(allowed: Boolean) {
        _isBackgroundAllowed.value = allowed
    }

    fun showToast(@StringRes messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            // "System" is an empty locale list, which is how Android is told to go back to following
            // the device. Handed to Locale() instead, as it used to be, it became a request for a
            // language named "system": choosing it left the app pinned to a locale that exists
            // nowhere rather than releasing it.
            val appLocale = if (language == SYSTEM_LANGUAGE) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.create(localeOfCode(language))
            }
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

    fun chooseAccentColor(color: Int) {
        viewModelScope.launch {
            settingsRepository.chooseAccentColor(color)
        }
    }

    fun setBackgroundStyle(style: BackgroundStyle) {
        viewModelScope.launch {
            settingsRepository.setBackgroundStyle(style)
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

    fun setInstaller(installerType: InstallerType) {
        viewModelScope.launch { installerSelection.select(installerType) }
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

    fun setSegmentedDownloads(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSegmentedDownloads(enabled)
        }
    }

    fun setPlayStoreInstallSource(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPlayStoreInstallSource(enabled)
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
            showToast(R.string.proxy_restart_required)
        }
    }

    fun setProxyHost(host: String) {
        viewModelScope.launch {
            settingsRepository.setProxyHost(host)
            showToast(R.string.proxy_restart_required)
        }
    }

    fun setProxyPort(port: String) {
        viewModelScope.launch {
            val portInt = port.toIntOrNull()
            if (portInt == null) {
                showToast(R.string.proxy_port_error_not_int)
            } else {
                settingsRepository.setProxyPort(portInt)
                showToast(R.string.proxy_restart_required)
            }
        }
    }

    fun setGithubToken(token: String) {
        viewModelScope.launch {
            settingsRepository.setGithubToken(token.trim())
            // Check the new value right away instead of leaving githubTokenInvalid to whatever it was
            // until some unrelated call happens to touch api.github.com next — so the warning banner
            // reacts immediately, confirming either that it's fixed or that the new value is still bad.
            externalApi.verifyGithubToken()
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

    fun setSplitViewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSplitViewEnabled(enabled)
        }
    }

    fun setAccentMatchesAppIcon(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAccentMatchesAppIcon(enabled)
        }
    }

    fun setConfirmBadgeAdd(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setConfirmBadgeAdd(enabled)
        }
    }

    /** Writes a new backup zip containing exactly [categories] to [uri] (an already-created document from
     *  a `CreateDocument` picker). */
    fun backup(uri: Uri, categories: Set<BackupCategory>) {
        viewModelScope.launch {
            backupRepository.createBackup(uri, categories).fold(
                onSuccess = { showToast(R.string.backup_success) },
                onFailure = { showToast(R.string.backup_failed) },
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
            .updateAsMutable { add(0, SYSTEM_LANGUAGE) }
    }
}
