package com.looker.droidify.compose.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.looker.droidify.compose.tv.TvAccentBackground
import com.looker.droidify.compose.tv.TvAccentHeader
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.settings.SettingsViewModel.Companion.cleanUpIntervals
import com.looker.droidify.compose.settings.SettingsViewModel.Companion.localeCodesList
import com.looker.droidify.compose.settings.components.ActionSettingItem
import com.looker.droidify.compose.settings.components.BackupCategoryDialog
import com.looker.droidify.compose.settings.components.CustomButtonsSettingItem
import com.looker.droidify.compose.settings.components.SelectionSettingItem
import com.looker.droidify.compose.settings.components.SettingHeader
import com.looker.droidify.compose.settings.components.SwitchSettingItem
import com.looker.droidify.compose.settings.components.TextInputSettingItem
import com.looker.droidify.compose.settings.components.ThemeColorPickerDialog
import com.looker.droidify.compose.settings.components.WarningBanner
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.LegacyInstallerComponent
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.datastore.model.Theme
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.extension.openLink
import com.looker.droidify.utility.common.isIgnoreBatteryEnabled
import com.looker.droidify.utility.common.requestBatteryFreedom
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration

private const val BACKUP_MIME_TYPE = "application/zip"

// Some file managers report a zip as application/octet-stream or application/x-zip-compressed instead
// of application/zip — accept all three so a real backup file is never greyed out in the picker
// (mirrors the same file-manager MIME-detection quirk the old per-category JSON import worked around).
private val RESTORE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

private fun defaultBackupFileName(): String =
    "omnify-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"

/** Localised label for a translation engine choice in the dropdown. */
@Composable
private fun translationEngineLabel(engine: TranslationEngine): String = stringResource(
    when (engine) {
        TranslationEngine.NONE -> R.string.translation_engine_none
        TranslationEngine.GOOGLE -> R.string.translation_engine_google
        TranslationEngine.LIBRETRANSLATE -> R.string.translation_engine_libretranslate
        TranslationEngine.MLKIT -> R.string.translation_engine_mlkit
    },
)

private const val FOXY_DROID_TITLE = "FoxyDroid"
private const val FOXY_DROID_AUTHOR = "kitsunyan"
private const val FOXY_DROID_URL = "https://github.com/kitsunyan/foxy-droid"
private const val DROID_IFY_ORIGINAL = "Original Droid-ify"
private const val DROID_IFY_URL = "https://github.com/Droid-ify/client"
private const val DROID_IFY_AUTHOR = "LooKeR"
private const val AUTHOR_NAME = "Victor-root"
private const val AUTHOR_REPO_URL = "https://github.com/Victor-root/Omnify"
private const val AUTHOR_GITHUB_URL = "https://github.com/Victor-root"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val isTelevision = LocalIsTelevision.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val customButtons by viewModel.customButtons.collectAsStateWithLifecycle()
    val isBackgroundAllowed by viewModel.isBackgroundAllowed.collectAsStateWithLifecycle()

    // Re-check on every resume — in particular when returning from the system battery-optimisation
    // dialog — so the warning banner clears as soon as the user grants access, not only after a
    // second tap (startActivity is async, so checking right after requesting it sees the old state).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.updateBackgroundAccessState(context.isIgnoreBatteryEnabled())
    }

    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()

    // The category checkboxes are confirmed before the file even exists (CreateDocument creates it),
    // so the selection has to be held here until that picker returns a Uri to actually write to.
    var pendingBackupCategories by remember { mutableStateOf<Set<BackupCategory>?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { uri ->
        val categories = pendingBackupCategories
        if (uri != null && categories != null) {
            viewModel.backup(uri, categories)
        }
        pendingBackupCategories = null
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.inspectRestoreFile(uri)
        } else {
            viewModel.showSnackbar(R.string.file_format_error_DESC)
        }
    }

    var showBackupDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    // TV / D-pad: drop focus from the header into the settings list (the top bar won't on its own).
    val contentFocusRequester = remember { FocusRequester() }

    // On TV the accent wash spans the whole screen (behind the header too, so there's no seam), so the
    // Scaffold is transparent and the wash is drawn behind it. On phone the Scaffold keeps its own
    // background and draws the wash inside the content as before.
    Box(modifier = Modifier.fillMaxSize()) {
        if (isTelevision) TvAccentBackground()
        Scaffold(
        containerColor = if (isTelevision) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            if (isTelevision) {
                // Match the other TV screens (a back affordance + a large accent title) instead of the
                // phone's solid accent bar.
                TvAccentHeader(
                    title = stringResource(R.string.settings),
                    onBackClick = onBackClick,
                    modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                )
            } else {
                TopAppBar(
                    colors = accentTopAppBarColors(),
                    expandedHeight = AccentBarHeight,
                    modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                    title = { Text(text = stringResource(R.string.settings)) },
                    navigationIcon = { BackButton(onBackClick) },
                )
            }
        },
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
    ) { contentPadding ->
        if (!isTelevision) FloatingAppCardsBackground(
            Modifier.padding(contentPadding.forFloatingBackground()),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .focusRequester(contentFocusRequester)
                .focusGroup(),
        ) {
            // Battery-optimisation exemption is a phone/handheld concern; Android TV has no such
            // setting, so never nag about it there.
            if (!isTelevision && !isBackgroundAllowed && settings.autoSync != AutoSync.NEVER) {
                item {
                    WarningBanner(
                        title = stringResource(R.string.require_background_access),
                        description = stringResource(R.string.require_background_access_DESC),
                        // The state refreshes on resume (see LifecycleEventEffect above) once the user
                        // returns from the system dialog, so the banner clears on its own.
                        onClick = { context.requestBatteryFreedom() },
                    )
                }
            }

            item {
                SettingHeader(title = stringResource(R.string.prefs_personalization))
            }

            item {
                LanguageSetting(
                    icon = painterResource(R.drawable.ic_language),
                    selectedLanguage = settings.language,
                    onLanguageSelected = viewModel::setLanguage,
                )
            }

            item {
                ThemeSetting(
                    icon = painterResource(R.drawable.ic_tabler_paint),
                    selectedTheme = settings.theme,
                    onThemeSelected = viewModel::setTheme,
                )
            }

            item {
                ActionSettingItem(
                    title = stringResource(R.string.theme_color),
                    description = stringResource(R.string.theme_color_DESC),
                    icon = painterResource(R.drawable.ic_tabler_palette),
                    onClick = { showColorPicker = true },
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.home_screen_swiping),
                    description = stringResource(R.string.home_screen_swiping_DESC),
                    icon = painterResource(R.drawable.ic_tabler_arrows_horizontal),
                    checked = settings.homeScreenSwiping,
                    onCheckedChange = viewModel::setHomeScreenSwiping,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.edge_to_edge),
                    description = stringResource(R.string.edge_to_edge_summary),
                    icon = painterResource(R.drawable.ic_tabler_maximize),
                    checked = settings.edgeToEdge,
                    onCheckedChange = viewModel::setEdgeToEdge,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.split_view_title),
                    description = stringResource(R.string.split_view_DESC),
                    icon = rememberVectorPainter(Icons.Filled.ViewColumn),
                    checked = settings.splitViewEnabled,
                    onCheckedChange = viewModel::setSplitViewEnabled,
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.updates))
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.auto_update),
                    description = stringResource(R.string.auto_update_apps),
                    icon = painterResource(R.drawable.ic_tabler_refresh),
                    checked = settings.autoUpdate,
                    onCheckedChange = viewModel::setAutoUpdate,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.notify_about_updates),
                    description = stringResource(R.string.notify_about_updates_summary),
                    icon = painterResource(R.drawable.ic_tabler_bell),
                    checked = settings.notifyUpdate,
                    onCheckedChange = viewModel::setNotifyUpdates,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.unstable_updates),
                    description = stringResource(R.string.unstable_updates_summary),
                    icon = painterResource(R.drawable.ic_bug_report),
                    checked = settings.unstableUpdate,
                    onCheckedChange = viewModel::setUnstableUpdates,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.incompatible_versions),
                    description = stringResource(R.string.incompatible_versions_summary),
                    icon = painterResource(R.drawable.ic_tabler_alert_triangle),
                    checked = settings.incompatibleVersions,
                    onCheckedChange = viewModel::setIncompatibleUpdates,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.ignore_signature),
                    description = stringResource(R.string.ignore_signature_summary),
                    icon = painterResource(R.drawable.ic_verified_off),
                    checked = settings.ignoreSignature,
                    onCheckedChange = viewModel::setIgnoreSignature,
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.sync_repositories))
            }

            item {
                AutoSyncSetting(
                    icon = painterResource(R.drawable.ic_sync),
                    selectedAutoSync = settings.autoSync,
                    onAutoSyncSelected = viewModel::setAutoSync,
                )
            }

            item {
                CleanUpIntervalSetting(
                    icon = painterResource(R.drawable.ic_time),
                    selectedInterval = settings.cleanUpInterval,
                    onIntervalSelected = viewModel::setCleanUpInterval,
                )
            }

            if (settings.cleanUpInterval == Duration.INFINITE) {
                item {
                    ActionSettingItem(
                        title = stringResource(R.string.force_clean_up),
                        description = stringResource(R.string.force_clean_up_DESC),
                        icon = painterResource(R.drawable.ic_tabler_trash),
                        onClick = { viewModel.forceCleanup(context) },
                    )
                }
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.download_stats),
                    description = stringResource(R.string.download_statistics_summary),
                    icon = painterResource(R.drawable.ic_download),
                    checked = settings.dlStatsEnabled,
                    onCheckedChange = viewModel::setDownloadStatisticsEnabled,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.reproducibility_logs),
                    description = stringResource(R.string.reproducibility_logs_summary),
                    icon = painterResource(R.drawable.ic_code),
                    checked = settings.rbLogsEnabled,
                    onCheckedChange = viewModel::setReproducibilityLogsEnabled,
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.install_types))
            }

            item {
                InstallerTypeSetting(
                    icon = painterResource(R.drawable.ic_apk_install),
                    selectedInstaller = settings.installerType,
                    onInstallerSelected = { viewModel.setInstaller(context, it) },
                )
            }

            if (settings.installerType == InstallerType.LEGACY) {
                item {
                    LegacyInstallerComponentSetting(
                        icon = painterResource(R.drawable.ic_tabler_package),
                        selectedComponent = settings.legacyInstallerComponent,
                        onComponentSelected = viewModel::setLegacyInstallerComponent,
                    )
                }
            }

            if (settings.installerType != InstallerType.LEGACY) {
                item {
                    SwitchSettingItem(
                        title = stringResource(R.string.delete_apk_on_install),
                        description = stringResource(R.string.delete_apk_on_install_summary),
                        icon = painterResource(R.drawable.ic_delete),
                        checked = settings.deleteApkOnInstall,
                        onCheckedChange = viewModel::setDeleteApkOnInstall,
                    )
                }
            }

            item {
                SettingHeader(title = stringResource(R.string.proxy))
            }

            item {
                ProxyTypeSetting(
                    icon = painterResource(R.drawable.ic_proxy),
                    selectedProxyType = settings.proxy.type,
                    onProxyTypeSelected = viewModel::setProxyType,
                )
            }

            if (settings.proxy.type != ProxyType.DIRECT) {
                item {
                    TextInputSettingItem(
                        title = stringResource(R.string.proxy_host),
                        value = settings.proxy.host,
                        icon = painterResource(R.drawable.ic_public),
                        onValueChange = viewModel::setProxyHost,
                    )
                }

                item {
                    TextInputSettingItem(
                        title = stringResource(R.string.proxy_port),
                        value = settings.proxy.port.toString(),
                        icon = painterResource(R.drawable.ic_public),
                        onValueChange = viewModel::setProxyPort,
                    )
                }
            }

            item {
                SettingHeader(title = stringResource(R.string.external_sources_title))
            }

            item {
                TextInputSettingItem(
                    title = stringResource(R.string.github_token),
                    value = settings.githubToken,
                    valueDisplay = if (settings.githubToken.isBlank()) {
                        stringResource(R.string.github_token_unset)
                    } else {
                        stringResource(R.string.github_token_set)
                    },
                    icon = painterResource(R.drawable.ic_github),
                    dialogTitle = stringResource(R.string.github_token),
                    helpText = stringResource(R.string.github_token_help),
                    onValueChange = viewModel::setGithubToken,
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.readme_javascript_title),
                    description = stringResource(R.string.readme_javascript_DESC),
                    checked = settings.readmeJavaScriptEnabled,
                    onCheckedChange = viewModel::setReadmeJavaScriptEnabled,
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.translation_section))
            }

            item {
                SelectionSettingItem(
                    title = stringResource(R.string.translation_engine),
                    icon = painterResource(R.drawable.ic_language),
                    selectedValue = settings.translationEngine,
                    values = TranslationEngine.entries.toList(),
                    onValueSelected = viewModel::setTranslationEngine,
                    valueToString = { translationEngineLabel(it) },
                )
            }

            item {
                SwitchSettingItem(
                    title = stringResource(R.string.auto_translate),
                    description = stringResource(R.string.auto_translate_DESC),
                    checked = settings.autoTranslate,
                    onCheckedChange = viewModel::setAutoTranslate,
                    // No point auto-translating when no engine is selected.
                    enabled = settings.translationEngine != TranslationEngine.NONE,
                )
            }

            if (settings.translationEngine == TranslationEngine.LIBRETRANSLATE) {
                item {
                    TextInputSettingItem(
                        title = stringResource(R.string.libretranslate_url),
                        value = settings.libreTranslateUrl,
                        valueDisplay = settings.libreTranslateUrl.ifBlank {
                            stringResource(R.string.libretranslate_url_unset)
                        },
                        icon = painterResource(R.drawable.ic_public),
                        helpText = stringResource(R.string.libretranslate_url_help),
                        onValueChange = viewModel::setLibreTranslateUrl,
                    )
                }
                item {
                    TextInputSettingItem(
                        title = stringResource(R.string.libretranslate_api_key),
                        value = settings.libreTranslateApiKey,
                        valueDisplay = if (settings.libreTranslateApiKey.isBlank()) {
                            stringResource(R.string.unspecified)
                        } else {
                            stringResource(R.string.github_token_set)
                        },
                        icon = painterResource(R.drawable.ic_public),
                        onValueChange = viewModel::setLibreTranslateApiKey,
                    )
                }
            }

            item {
                SettingHeader(title = stringResource(R.string.import_export))
            }

            item {
                ActionSettingItem(
                    title = stringResource(R.string.backup_title),
                    description = stringResource(R.string.backup_row_DESC),
                    icon = painterResource(R.drawable.ic_save),
                    onClick = { showBackupDialog = true },
                )
            }

            item {
                ActionSettingItem(
                    title = stringResource(R.string.restore_title),
                    description = stringResource(R.string.restore_row_DESC),
                    icon = painterResource(R.drawable.ic_download),
                    onClick = { restoreLauncher.launch(RESTORE_MIME_TYPES) },
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.custom_buttons_section))
            }

            item {
                CustomButtonsSettingItem(
                    buttons = customButtons,
                    onAddButton = viewModel::addCustomButton,
                    onUpdateButton = viewModel::updateCustomButton,
                    onRemoveButton = viewModel::removeCustomButton,
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.author))
            }

            item {
                AuthorRow(
                    icon = painterResource(R.drawable.ic_person),
                    title = AUTHOR_NAME,
                    subtitle = stringResource(R.string.author_repo_subtitle),
                    onClick = { context.openLink(AUTHOR_REPO_URL) },
                )
            }

            item {
                AuthorRow(
                    icon = painterResource(R.drawable.ic_github),
                    title = stringResource(R.string.follow_on_github),
                    subtitle = null,
                    onClick = { context.openLink(AUTHOR_GITHUB_URL) },
                )
            }

            item {
                SettingHeader(title = stringResource(R.string.special_credits))
            }

            item {
                ActionSettingItem(
                    title = FOXY_DROID_TITLE,
                    description = FOXY_DROID_AUTHOR,
                    icon = painterResource(R.drawable.ic_github),
                    onClick = { context.openLink(FOXY_DROID_URL) },
                )
            }

            item {
                ActionSettingItem(
                    title = DROID_IFY_AUTHOR,
                    description = DROID_IFY_ORIGINAL,
                    icon = painterResource(R.drawable.ic_github),
                    onClick = { context.openLink(DROID_IFY_URL) },
                )
            }

            item { VersionFooter() }
        }
    }
    }

    if (showColorPicker) {
        ThemeColorPickerDialog(
            selectedColor = settings.themeColor,
            dynamicEnabled = settings.dynamicTheme,
            showWallpaperOption = SdkCheck.isSnowCake,
            onColorSelected = { color ->
                if (settings.dynamicTheme) viewModel.setDynamicTheme(false)
                viewModel.setThemeColor(color)
                showColorPicker = false
            },
            onWallpaperSelected = {
                viewModel.setDynamicTheme(true)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }

    if (showBackupDialog) {
        BackupCategoryDialog(
            title = stringResource(R.string.backup_dialog_title),
            confirmLabel = stringResource(R.string.backup_title),
            availableCategories = BackupCategory.entries.toSet(),
            onConfirm = { categories ->
                pendingBackupCategories = categories
                showBackupDialog = false
                backupLauncher.launch(defaultBackupFileName())
            },
            onDismiss = { showBackupDialog = false },
        )
    }

    val restoreInspection = pendingRestore
    if (restoreInspection != null) {
        BackupCategoryDialog(
            title = stringResource(R.string.restore_dialog_title),
            confirmLabel = stringResource(R.string.restore_title),
            availableCategories = restoreInspection.availableCategories,
            onConfirm = { categories -> viewModel.confirmRestore(categories) },
            onDismiss = { viewModel.cancelRestore() },
        )
    }
}

/** The app version at the very bottom of the settings, as a normal settings row (icon + title +
 *  subtitle) so it matches the rest. The subtitle carries the version and the build type
 *  (debug/release/alpha) so it's clear which build is installed. */
@Composable
private fun VersionFooter() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The app's own launcher icon (its colour foreground layer), shown as an Image so the footer
        // reads as "this is Omnify". We use the foreground drawable, not R.mipmap.ic_launcher: the
        // latter resolves to the adaptive-icon XML on API 26+, which painterResource can't load (it
        // only supports vector drawables and bitmaps), and that crashed the screen.
        //
        // The box is sized and spaced exactly like every other row's leading icon (SettingLeadingIcon:
        // 24dp + a 20dp gap) so this row stays aligned with the rest. But an adaptive-icon foreground
        // layer reserves a large transparent safe zone around the glyph (so it survives being cropped
        // to a circle/square/etc. on the home screen) — drawn at 1:1 in a 24dp box, the actual glyph
        // came out tiny. Scale the image up around its centre and clip it to the 24dp box, cropping
        // away that safe-zone padding so the glyph itself fills the row icon like the others do.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = 2.3f
                    scaleY = 2.3f
                    clip = true
                },
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                text = stringResource(R.string.application_name),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${stringResource(R.string.version_FORMAT, BuildConfig.VERSION_NAME)} · " +
                    BuildConfig.BUILD_TYPE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A credits/author row with a leading icon, a title and an optional subtitle (e.g. the maintainer's
 *  name with "original version by …", or a "Follow on GitHub" link). */
@Composable
private fun AuthorRow(
    icon: Painter,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // TV only: a soft accent fill behind the focused row (no-op on touch).
            .tvFocusFill(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LanguageSetting(
    icon: Painter,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    // Android 13+ has a per-app language screen in the system settings; the app's locale is managed via
    // AppCompat, which is backed by the framework there, so the two are the same. Open that screen
    // instead of the in-app picker. Older versions have no such screen, so they keep the in-app picker.
    if (SdkCheck.isTiramisu) {
        ActionSettingItem(
            title = stringResource(R.string.prefs_language_title),
            description = context.translateLocale(context.getLocaleOfCode(selectedLanguage)),
            icon = icon,
            onClick = {
                val uri = "package:${context.packageName}".toUri()
                val opened = runCatching {
                    context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, uri))
                }.isSuccess
                // Fall back to the app's system settings page if the locale screen isn't available.
                if (!opened) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    }
                }
            },
        )
    } else {
        SelectionSettingItem(
            title = stringResource(R.string.prefs_language_title),
            icon = icon,
            selectedValue = selectedLanguage,
            values = localeCodesList,
            onValueSelected = onLanguageSelected,
            valueToString = { code ->
                context.translateLocale(context.getLocaleOfCode(code))
            },
        )
    }
}

@Composable
private fun ThemeSetting(
    icon: Painter,
    selectedTheme: Theme,
    onThemeSelected: (Theme) -> Unit,
) {
    SelectionSettingItem(
        title = stringResource(R.string.theme),
        icon = icon,
        dialogTitle = stringResource(R.string.themes),
        selectedValue = selectedTheme,
        values = Theme.entries,
        onValueSelected = onThemeSelected,
        valueToString = { theme ->
            when (theme) {
                Theme.SYSTEM -> stringResource(R.string.system)
                Theme.SYSTEM_BLACK -> "${stringResource(R.string.system)} ${stringResource(R.string.amoled)}"
                Theme.LIGHT -> stringResource(R.string.light)
                Theme.DARK -> stringResource(R.string.dark)
                Theme.AMOLED -> stringResource(R.string.amoled)
            }
        },
    )
}

@Composable
private fun AutoSyncSetting(
    icon: Painter,
    selectedAutoSync: AutoSync,
    onAutoSyncSelected: (AutoSync) -> Unit,
) {
    SelectionSettingItem(
        title = stringResource(R.string.sync_repositories_automatically),
        icon = icon,
        selectedValue = selectedAutoSync,
        values = AutoSync.entries,
        onValueSelected = onAutoSyncSelected,
        valueToString = { autoSync ->
            when (autoSync) {
                AutoSync.NEVER -> stringResource(R.string.never)
                AutoSync.WIFI_ONLY -> stringResource(R.string.only_on_wifi)
                AutoSync.WIFI_PLUGGED_IN -> stringResource(R.string.only_on_wifi_with_charging)
                AutoSync.ALWAYS -> stringResource(R.string.always)
            }
        },
    )
}

@Composable
private fun CleanUpIntervalSetting(
    icon: Painter,
    selectedInterval: Duration,
    onIntervalSelected: (Duration) -> Unit,
) {
    SelectionSettingItem(
        title = stringResource(R.string.cleanup_title),
        icon = icon,
        selectedValue = selectedInterval,
        values = cleanUpIntervals,
        onValueSelected = onIntervalSelected,
        valueToString = { duration -> duration.toDisplayString() },
    )
}

@Composable
private fun InstallerTypeSetting(
    icon: Painter,
    selectedInstaller: InstallerType,
    onInstallerSelected: (InstallerType) -> Unit,
) {
    SelectionSettingItem(
        title = stringResource(R.string.installer),
        icon = icon,
        selectedValue = selectedInstaller,
        values = InstallerType.entries,
        onValueSelected = onInstallerSelected,
        valueToString = { installer ->
            when (installer) {
                InstallerType.LEGACY -> stringResource(R.string.legacy_installer)
                InstallerType.SESSION -> stringResource(R.string.session_installer)
                InstallerType.SHIZUKU -> stringResource(R.string.shizuku_installer)
                InstallerType.ROOT -> stringResource(R.string.root_installer)
            }
        },
    )
}

@Composable
private fun LegacyInstallerComponentSetting(
    icon: Painter,
    selectedComponent: LegacyInstallerComponent?,
    onComponentSelected: (LegacyInstallerComponent?) -> Unit,
) {
    val context = LocalContext.current
    val installerOptions = remember { context.getInstallerOptions() }

    SelectionSettingItem(
        title = stringResource(R.string.legacyInstallerComponent),
        icon = icon,
        selectedValue = selectedComponent ?: LegacyInstallerComponent.Unspecified,
        values = installerOptions,
        onValueSelected = onComponentSelected,
        valueToString = { component ->
            when (component) {
                is LegacyInstallerComponent.Component -> {
                    val appLabel = runCatching {
                        val info = context.packageManager.getApplicationInfo(component.clazz, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    }.getOrElse { component.clazz }
                    "$appLabel (${component.activity})"
                }

                LegacyInstallerComponent.Unspecified -> stringResource(R.string.unspecified)
                LegacyInstallerComponent.AlwaysChoose -> stringResource(R.string.always_choose)
            }
        },
    )
}

@Composable
private fun ProxyTypeSetting(
    icon: Painter,
    selectedProxyType: ProxyType,
    onProxyTypeSelected: (ProxyType) -> Unit,
) {
    SelectionSettingItem(
        title = stringResource(R.string.proxy_type),
        icon = icon,
        selectedValue = selectedProxyType,
        values = ProxyType.entries,
        onValueSelected = onProxyTypeSelected,
        valueToString = { proxyType ->
            when (proxyType) {
                ProxyType.DIRECT -> stringResource(R.string.no_proxy)
                ProxyType.HTTP -> stringResource(R.string.http_proxy)
                ProxyType.SOCKS -> stringResource(R.string.socks_proxy)
            }
        },
    )
}

@Composable
private fun Duration.toDisplayString(): String {
    if (this == Duration.INFINITE) return stringResource(R.string.never)
    val hours = inWholeHours.toInt()
    val days = inWholeDays.toInt()
    return if (hours >= 24) {
        "$days " + pluralStringResource(R.plurals.days, days)
    } else {
        "$hours " + pluralStringResource(R.plurals.hours, hours)
    }
}

@Suppress("DEPRECATION")
private fun Context.getInstallerOptions(): List<LegacyInstallerComponent> {
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType("content://".toUri(), "application/vnd.android.package-archive")
    }
    val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return listOf(
        LegacyInstallerComponent.Unspecified,
        LegacyInstallerComponent.AlwaysChoose,
    ) + activities.map {
        LegacyInstallerComponent.Component(
            clazz = it.activityInfo.packageName,
            activity = it.activityInfo.name,
        )
    }
}

@Suppress("DEPRECATION")
private fun Context.getLocaleOfCode(localeCode: String): Locale? = when {
    localeCode.isEmpty() -> if (SdkCheck.isNougat) {
        resources.configuration.locales[0]
    } else {
        resources.configuration.locale
    }

    localeCode.contains("-r") -> Locale(localeCode.substring(0, 2), localeCode.substring(4))
    localeCode.contains("_") -> Locale(localeCode.substring(0, 2), localeCode.substring(3))
    localeCode == "system" -> null
    else -> Locale(localeCode)
}

private fun Context.translateLocale(locale: Locale?): String {
    val country = locale?.getDisplayCountry(locale)
    val language = locale?.getDisplayLanguage(locale)
    return if (locale != null) {
        val capitalizedLanguage = language?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val countrySuffix = if (country?.isNotEmpty() == true && country.compareTo(
                language.toString(),
                ignoreCase = true,
            ) != 0
        ) {
            "($country)"
        } else {
            ""
        }
        "$capitalizedLanguage$countrySuffix"
    } else {
        getString(R.string.system)
    }
}
