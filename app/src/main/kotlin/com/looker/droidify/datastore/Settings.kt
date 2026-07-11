package com.looker.droidify.datastore

import androidx.datastore.core.Serializer
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.LegacyInstallerComponent
import com.looker.droidify.datastore.model.ProxyPreference
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.datastore.model.TranslationEngine
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Default accent colour: Material Green #4CAF50 — Android's colour, and a good match for the logo.
 * Applied raw (vivid) as the primary/accent; the surface roles are generated from it.
 */
val DEFAULT_THEME_COLOR: Int = 0xFF4CAF50.toInt()

@Serializable
@OptIn(ExperimentalTime::class)
data class Settings(
    val language: String = "system",
    val incompatibleVersions: Boolean = false,
    val notifyUpdate: Boolean = true,
    val unstableUpdate: Boolean = false,
    val ignoreSignature: Boolean = false,
    val theme: Theme = Theme.SYSTEM,
    val dynamicTheme: Boolean = false,
    val themeColor: Int = DEFAULT_THEME_COLOR,
    val edgeToEdge: Boolean = false,
    val installerType: InstallerType = InstallerType.Default,
    val legacyInstallerComponent: LegacyInstallerComponent? = null,
    val autoUpdate: Boolean = false,
    val autoSync: AutoSync = AutoSync.WIFI_ONLY,
    val sortOrder: SortOrder = SortOrder.UPDATED,
    val proxy: ProxyPreference = ProxyPreference(),
    val cleanUpInterval: Duration = 12.hours,
    @Contextual
    val lastCleanup: Instant? = null,
    val lastRbLogFetch: Long? = null,
    val lastModifiedDownloadStats: Long? = null,
    val favouriteApps: Set<String> = emptySet(),
    val homeScreenSwiping: Boolean = false,
    val enabledRepoIds: Set<Int> = emptySet(),
    val deleteApkOnInstall: Boolean = false,
    val dlStatsEnabled: Boolean = true,
    val rbLogsEnabled: Boolean = true,
    /** Optional GitHub personal access token (no scopes needed). When set, external-source requests to
     *  api.github.com are authenticated, raising the rate limit from 60 to 5000 requests/hour. */
    val githubToken: String = "",
    /** Backend for the "Translate" button on app descriptions. */
    val translationEngine: TranslationEngine = TranslationEngine.NONE,
    /** Base URL of the user's LibreTranslate instance (used only when [translationEngine] is
     *  LIBRETRANSLATE), e.g. "https://translate.example.org". */
    val libreTranslateUrl: String = "",
    /** Optional API key for the LibreTranslate instance (some require one). */
    val libreTranslateApiKey: String = "",
    /** Automatically translate an app's description on open when it isn't already in the device
     *  language. */
    val autoTranslate: Boolean = false,
    /** Whether the README WebView on external-source detail pages may run embedded JavaScript (e.g. a
     *  dynamic star-history chart or badge some projects embed). Off by default (most READMEs render
     *  identically either way); on only helps the rare README whose dynamic content needs it. Never
     *  affects the app's own UI. */
    val readmeJavaScriptEnabled: Boolean = false,
    /** Tablet-landscape-only two-pane app detail layout (hero card fixed on the left, the rest of the
     *  page scrolling on the right). On by default on eligible screens; off hides the feature entirely
     *  for users who don't want it, regardless of the per-screen toggle button. */
    val splitViewEnabled: Boolean = true,
)

@OptIn(ExperimentalSerializationApi::class)
object SettingsSerializer : Serializer<Settings> {

    private val json = Json { encodeDefaults = true }

    @OptIn(ExperimentalTime::class)
    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings {
        return try {
            json.decodeFromStream(input)
        } catch (e: SerializationException) {
            e.printStackTrace()
            defaultValue
        }
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        try {
            json.encodeToStream(t, output)
        } catch (e: SerializationException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
