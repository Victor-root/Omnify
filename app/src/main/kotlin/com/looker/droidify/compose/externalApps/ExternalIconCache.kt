package com.looker.droidify.compose.externalApps

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent cache of the *real* app icons extracted from external apps' APKs, keyed by
 * [com.looker.droidify.external.ExternalApp.key]. A release ships no icon metadata, so the only
 * reliable source is the APK itself (read once, then cached here as a PNG).
 */
object ExternalIconCache {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun iconFile(context: Context, key: String): File {
        val dir = File(context.filesDir, "external_icons").apply { mkdirs() }
        return File(dir, key.replace(unsafeChars, "_") + ".png")
    }

    fun save(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(iconFile(context, key)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
