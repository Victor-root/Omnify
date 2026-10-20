package com.looker.droidify.compose.welcome

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.settings.components.RestoreCategoriesDialog
import com.looker.droidify.compose.settings.components.rememberRestoreFilePicker
import com.looker.droidify.compose.settings.transfer.DeviceTransferReceiveDialog
import com.looker.droidify.migration.ImportStep
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.areNotificationsEnabled
import com.looker.droidify.utility.common.canRequestPackageInstalls
import com.looker.droidify.utility.common.openNotificationSettings
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings

/** Something a welcome page offers behind a button: [onClick] goes and does it, [done] is whether it
 *  has been done, which swaps the button for a tick. By default it asks the person to allow something. */
internal class WelcomeStep(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val done: Boolean,
    val onClick: () -> Unit,
    @param:StringRes val actionLabel: Int = R.string.allow,
    @param:StringRes val doneLabel: Int = R.string.welcome_allowed,
    val enabled: Boolean = true,
)

/**
 * The permissions the first page asks for, each behind a button of its own rather than a system dialog
 * out of nowhere: notifications, which open Android's own prompt, and the install of apps, which is
 * given on a system page.
 *
 * Both are read again every time the app comes back to the front, since both are answered outside it.
 * Android only asks for notifications a limited number of times, so once it has been asked here the
 * button leads to the app's notification settings instead, where it always works.
 */
@Composable
internal fun rememberWelcomeSteps(): List<WelcomeStep> {
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.areNotificationsEnabled()) }
    var canInstallApps by remember { mutableStateOf(context.canRequestPackageInstalls()) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = context.areNotificationsEnabled() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = context.areNotificationsEnabled()
        canInstallApps = context.canRequestPackageInstalls()
    }
    return listOf(
        WelcomeStep(
            icon = R.drawable.ic_tabler_bell,
            title = R.string.welcome_notifications_title,
            description = R.string.welcome_notifications_hint,
            done = notificationsAllowed,
            onClick = {
                // Below Android 13 there is no permission to ask for, only the app's own switch.
                if (notificationsAsked || !SdkCheck.isTiramisu) {
                    context.openNotificationSettings()
                } else {
                    notificationsAsked = true
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        ),
        WelcomeStep(
            icon = R.drawable.ic_apk_install,
            title = R.string.welcome_install_title,
            description = R.string.welcome_install_hint,
            done = canInstallApps,
            onClick = { context.openUnknownAppSourcesSettings() },
        ),
    )
}

/**
 * The ways to bring data over, each behind a button: a backup file, another device, and an Omnify Beta
 * still on the device (only offered when there is one). All three end in the same restore that
 * Settings offers, so none of them can do anything the others cannot.
 *
 * Hosts what the buttons open, as [rememberWelcomeSteps] hosts its launcher: the dialog that asks which
 * categories of a file to apply (so what is not wanted is simply left out), and the one that receives
 * data from another device. Nothing here is required: the page is passed by going on.
 */
@Composable
internal fun rememberRestoreSteps(viewModel: WelcomeViewModel): List<WelcomeStep> {
    val fileRestored by viewModel.fileRestored.collectAsStateWithLifecycle()
    val betaImport by viewModel.betaImport.collectAsStateWithLifecycle()
    val deviceRestored by viewModel.deviceRestored.collectAsStateWithLifecycle()
    // Not saveable, unlike the rest of this page: applying what arrived can restore a theme, which
    // recreates the activity, and a saved flag reopened the dialog on a fresh code straight after.
    var receiving by remember { mutableStateOf(false) }
    val pickFile = rememberRestoreFilePicker(viewModel.restore)
    RestoreCategoriesDialog(viewModel.restore, onRestored = viewModel::onFileRestored)
    if (receiving) {
        DeviceTransferReceiveDialog(
            onDismiss = { receiving = false },
            onApplied = viewModel::onDeviceRestored,
        )
    }
    return listOfNotNull(
        WelcomeStep(
            icon = R.drawable.ic_download,
            title = R.string.welcome_restore_file_title,
            description = R.string.welcome_restore_file_hint,
            done = fileRestored,
            onClick = pickFile,
            actionLabel = R.string.welcome_restore_file_action,
            doneLabel = R.string.welcome_restored,
        ),
        WelcomeStep(
            icon = R.drawable.ic_tabler_home_down,
            title = R.string.transfer_receive_title,
            description = R.string.welcome_restore_device_hint,
            done = deviceRestored,
            onClick = { receiving = true },
            actionLabel = R.string.welcome_restore_device_action,
            doneLabel = R.string.welcome_restored,
        ),
        betaImport?.let { step ->
            val imported = step == ImportStep.IMPORTED
            WelcomeStep(
                icon = R.drawable.ic_tabler_package,
                title = when (step) {
                    ImportStep.IMPORTED -> R.string.migration_imported_title
                    ImportStep.FAILED -> R.string.migration_failed_title
                    else -> R.string.welcome_restore_beta_title
                },
                description = when (step) {
                    ImportStep.IMPORTED -> R.string.migration_imported_DESC
                    ImportStep.FAILED -> R.string.migration_failed_DESC
                    else -> R.string.migration_import_DESC
                },
                done = false,
                onClick = if (imported) viewModel::uninstallBeta else viewModel::importFromBeta,
                actionLabel = if (imported) R.string.migration_uninstall_beta else R.string.migration_import_action,
                enabled = step != ImportStep.RUNNING,
            )
        },
    )
}
