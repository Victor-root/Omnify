package com.looker.droidify.utility.apk

import com.looker.droidify.utility.common.LanguageDetector

/**
 * The language of an app that keeps its text in its code rather than in string resources.
 *
 * Such an app has nothing for [ApkResourceLocales] to read, yet what it shows the user is all there in
 * its `classes.dex`, in the table of every string the code uses. Prochain train, French only and under
 * 100 KB, is one: a resource table of nothing but drawables, which reads as an English app.
 *
 * Only sentences are looked at, so class names, keys and addresses don't count. Whatever a library logs
 * counts as much as what the app says, so a large app with no string resources can come out English; it
 * was going to be called that anyway.
 */
internal object DexText {

    private val DEX_MAGIC = "dex\n".toByteArray(Charsets.US_ASCII)
    private const val STRING_IDS_SIZE_OFFSET = 56
    private const val STRING_IDS_OFFSET_OFFSET = 60
    private const val HEADER_SIZE = 112

    /** The language of [dex]'s sentences, null when it isn't a dex file or they don't tell. Never
     *  throws, since [dex] comes from an untrusted remote server. */
    fun language(dex: ByteArray): String? = runCatching { LanguageDetector.detect(sentences(dex)) }.getOrNull()

    /** Every string of [dex] holding a space and a letter, separated by spaces. */
    private fun sentences(dex: ByteArray): String {
        if (dex.size < HEADER_SIZE || DEX_MAGIC.indices.any { dex[it] != DEX_MAGIC[it] }) return ""
        val count = u32(dex, STRING_IDS_SIZE_OFFSET)
        val table = u32(dex, STRING_IDS_OFFSET_OFFSET)
        if (count < 0 || table < 0 || table.toLong() + count.toLong() * Int.SIZE_BYTES > dex.size) return ""
        return buildString {
            for (index in 0 until count) {
                val string = stringAt(dex, u32(dex, table + index * Int.SIZE_BYTES)) ?: continue
                if (string.any { it == ' ' } && string.any { it.isLetter() }) {
                    append(string)
                    append(' ')
                }
            }
        }
    }

    /** One `string_data_item`: its length as a ULEB128, of no use here, then NUL-terminated MUTF-8, which
     *  matches UTF-8 for all a sentence is made of. */
    private fun stringAt(dex: ByteArray, offset: Int): String? {
        if (offset < 0 || offset >= dex.size) return null
        var start = offset
        while (start < dex.size && dex[start] < 0) start++
        start++
        var end = start
        while (end < dex.size && dex[end] != 0.toByte()) end++
        return if (end < dex.size) String(dex, start, end - start, Charsets.UTF_8) else null
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
