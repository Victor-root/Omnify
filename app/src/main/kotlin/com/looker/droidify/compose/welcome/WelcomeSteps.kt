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
import com.looker.droidify.R
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.areNotificationsEnabled
import com.looker.droidify.utility.common.canRequestPackageInstalls
import com.looker.droidify.utility.common.openNotificationSettings
import com.looker.droidify.utility.common.openUnknownAppSourcesSettings

/** Something the first page asks the person to allow: [onClick] goes and gets it, [done] is whether it
 *  has been given. */
internal class WelcomeStep(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val done: Boolean,
    val onClick: () -> Unit,
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
