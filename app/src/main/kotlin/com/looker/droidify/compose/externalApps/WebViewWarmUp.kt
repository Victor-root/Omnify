package com.looker.droidify.compose.externalApps

import android.content.Context
import android.os.SystemClock
import android.webkit.WebSettings
import com.looker.droidify.BuildConfig
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads WebView's engine once per run, ahead of the first [ReadmeWebView]. The first one of a run was
 * built in 1.0 to 1.3 s on a SHIELD TV, against 4 to 20 ms for every one after it, all of it while the
 * app page that needed it waited on screen. Asking for the default user agent loads the engine without
 * building a view, and off the main thread.
 */
object WebViewWarmUp {
    private val started = AtomicBoolean(false)

    suspend fun run(context: Context) {
        if (!started.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val loaded = runCatching { WebSettings.getDefaultUserAgent(context) }.isSuccess
            if (BuildConfig.DIAGNOSTICS) {
                DiagnosticLog.log(
                    "WebView engine loaded ahead in ${SystemClock.elapsedRealtime() - startedAt} ms, success=$loaded",
                )
            }
        }
    }
}
