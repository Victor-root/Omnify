package com.looker.droidify.utility.common

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * The locale Android has this app running under, or null when it simply follows the device.
 *
 * Only Android 13 and up has a per-app language at all, and this asks the framework for it directly.
 *
 * `AppCompatDelegate.getApplicationLocales()` looks like the portable way to ask, and is what this app
 * used, but it answers out of AppCompat's own set of activities: it walks the live `AppCompatDelegate`s
 * and asks the first one's context. This app's single activity is a plain `ComponentActivity`, so that
 * set is empty, the answer was always "nothing is set", and the language row said "System" however
 * plainly the app was running in English. Its setter is the same shape and equally inert here, which is
 * why nothing this app wrote through it ever reached Android either.
 */
fun Context.applicationLocale(): Locale? =
    if (SdkCheck.isTiramisu) frameworkApplicationLocale() else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Context.frameworkApplicationLocale(): Locale? {
    val locales = getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
    return if (locales.isEmpty) null else locales[0]
}
