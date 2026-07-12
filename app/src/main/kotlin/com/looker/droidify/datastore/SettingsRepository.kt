package com.looker.droidify.datastore

import android.net.Uri
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.LegacyInstallerComponent
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.datastore.model.TranslationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.*
import kotlin.time.Duration

interface SettingsRepository {

    val data: Flow<Settings>

    suspend fun getInitial(): Settings

    suspend fun export(target: Uri)

    suspend fun import(target: Uri)

    suspend fun setLanguage(language: String)

    suspend fun enableIncompatibleVersion(enable: Boolean)

    suspend fun enableNotifyUpdates(enable: Boolean)

    suspend fun enableUnstableUpdates(enable: Boolean)

    suspend fun setIgnoreSignature(enable: Boolean)

    suspend fun setTheme(theme: Theme)

    suspend fun setDynamicTheme(enable: Boolean)

    suspend fun setThemeColor(color: Int)

    suspend fun setEdgeToEdge(enable: Boolean)

    suspend fun setInstallerType(installerType: InstallerType)

    suspend fun setLegacyInstallerComponent(component: LegacyInstallerComponent?)

    suspend fun setAutoUpdate(allow: Boolean)

    suspend fun setAutoSync(autoSync: AutoSync)

    suspend fun setSortOrder(sortOrder: SortOrder)

    suspend fun setProxyType(proxyType: ProxyType)

    suspend fun setProxyHost(proxyHost: String)

    suspend fun setProxyPort(proxyPort: Int)

    suspend fun setCleanUpInterval(interval: Duration)

    suspend fun setCleanupInstant()

    suspend fun setRbLogLastModified(date: Date)

    suspend fun updateLastModifiedDownloadStats(date: Date)

    suspend fun setHomeScreenSwiping(value: Boolean)

    suspend fun toggleFavourites(packageName: String)

    suspend fun toggleRepoSectionCollapsed(sectionKey: String)

    suspend fun setRepoEnabled(repoId: Int, enabled: Boolean)

    fun getEnabledRepoIds(): Flow<Set<Int>>

    suspend fun isRepoEnabled(repoId: Int): Boolean

    suspend fun setDeleteApkOnInstall(enable: Boolean)

    suspend fun setDownloadStatisticsEnabled(enabled: Boolean)

    suspend fun clearDownloadStatsLastModified()

    suspend fun setRBLogsEnabled(enabled: Boolean)

    suspend fun clearRbLogLastModified()

    suspend fun setGithubToken(token: String)

    suspend fun setTranslationEngine(engine: TranslationEngine)

    suspend fun setLibreTranslateUrl(url: String)

    suspend fun setLibreTranslateApiKey(key: String)

    suspend fun setAutoTranslate(enable: Boolean)

    suspend fun setReadmeJavaScriptEnabled(enable: Boolean)

    suspend fun setSplitViewEnabled(enable: Boolean)
}

inline fun <T> SettingsRepository.get(crossinline block: suspend Settings.() -> T): Flow<T> {
    return data.map(block).distinctUntilChanged()
}
