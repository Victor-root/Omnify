package com.looker.droidify.external

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent cache of the *real* icon for each external app, keyed by [ExternalApp.key] and stored as
 * a PNG. A release ships no icon metadata at all, so this holds whichever best answer has been worked
 * out: the repository's adaptive icon composed as Android would draw it ([AdaptiveIconComposer]) before
 * the app is installed, then the icon read straight out of the APK once it has been.
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
