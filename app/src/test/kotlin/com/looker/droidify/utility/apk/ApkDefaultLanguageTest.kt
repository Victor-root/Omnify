package com.looker.droidify.utility.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Default" is not "English": what the default config says decides what language it counts as. */
class ApkDefaultLanguageTest {

    private val french = listOf(
        "Aucun passage prévu pour le moment sur cette ligne",
        "Ajoutez une gare ou un arrêt pour voir les prochains départs",
        "Les horaires ne sont pas disponibles pour le moment",
        "Vous pouvez choisir une direction dans la liste des lignes",
        "La clé est nécessaire pour avoir les données de la ligne",
    )
    private val english = listOf(
        "There is nothing to show for this line at the moment",
        "You can add a station to see what is leaving from it",
        "The timetable is not available for the moment",
        "You have to choose a direction in the list of lines",
        "This key is needed to get the data of the line",
    )
    private val german = listOf(
        "Für diese Linie gibt es im Moment keine Abfahrten",
        "Fügen Sie eine Haltestelle hinzu und wählen Sie die Richtung",
        "Der Fahrplan ist im Moment nicht verfügbar",
    )

    @Test
    fun `an app written in French with no other language is French`() {
        assertEquals(listOf("fr"), ApkResourceLocales.localeCodes(table("" to french)))
    }

    @Test
    fun `a French base stays beside its translations`() {
        val locales = ApkResourceLocales.localeCodes(table("" to french, "de" to german + german))
        assertEquals(setOf("fr", "de"), locales.orEmpty().toSet())
    }

    @Test
    fun `an explicit English translation stays English beside a French base`() {
        val locales = ApkResourceLocales.localeCodes(table("" to french, "en" to english))
        assertEquals(setOf("fr", "en"), locales.orEmpty().toSet())
    }

    @Test
    fun `an app written in English is English`() {
        val arsc = table("" to english)
        assertEquals(listOf("en"), ApkResourceLocales.localeCodes(arsc))
        assertFalse(ApkResourceLocales.isUnprovenEnglish(arsc, listOf("en")))
    }

    @Test
    fun `an English app naming other languages stays English`() {
        val arsc = table("" to english + listOf("简体中文 繁體中文 日本語", "한국어 Ελληνικά"))
        assertEquals(listOf("en"), ApkResourceLocales.localeCodes(arsc))
    }

    @Test
    fun `an app written in Chinese with a few English words is Chinese`() {
        val chinese = listOf("保持代理服务在后台持续运行开启后即使从最近任务中划掉应用", "代理也会自动恢复 Proxy")
        assertEquals(listOf("zh"), ApkResourceLocales.localeCodes(table("" to chinese)))
    }

    @Test
    fun `too little text proves nothing`() {
        val arsc = table("" to listOf("Prochain train"))
        assertEquals(listOf("en"), ApkResourceLocales.localeCodes(arsc))
        assertTrue(ApkResourceLocales.isUnprovenEnglish(arsc, listOf("en")))
    }

    @Test
    fun `a dependency's strings say nothing of the app`() {
        assertEquals(emptyList(), ApkResourceLocales.localeCodes(table("" to french, keyPrefix = "abc_")))
    }

    /** A resource table of one `string` type, one config per pair, a config by its language code. */
    private fun table(vararg configs: Pair<String, List<String>>, keyPrefix: String = "text_"): ByteArray {
        val values = configs.flatMap { it.second }.distinct()
        val count = configs.maxOf { it.second.size }
        val keys = List(count) { "$keyPrefix$it" }
        val typeChunks = configs.map { (language, strings) ->
            typeChunk(language, strings.map(values::indexOf), count)
        }
        val body = pool(listOf("string")) + pool(keys)
        val chunks = typeChunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
        val pkg = buffer(PACKAGE_HEADER + body.size + chunks.size)
        pkg.putShort(0, 0x0200).putShort(2, PACKAGE_HEADER.toShort())
        pkg.putInt(4, pkg.capacity()).putInt(8, 0x7F)
        pkg.putInt(268, PACKAGE_HEADER).putInt(276, PACKAGE_HEADER + pool(listOf("string")).size)
        pkg.position(PACKAGE_HEADER)
        pkg.put(body).put(chunks)
        val valuePool = pool(values)
        val out = buffer(TABLE_HEADER + valuePool.size + pkg.capacity())
        out.putShort(0, 0x0002).putShort(2, TABLE_HEADER.toShort())
        out.putInt(4, out.capacity()).putInt(8, 1)
        out.position(TABLE_HEADER)
        out.put(valuePool).put(pkg.array())
        return out.array()
    }

    /** A `ResTable_type` of [count] slots, each slot's [valueIndexes] entry a string of the value pool. */
    private fun typeChunk(language: String, valueIndexes: List<Int>, count: Int): ByteArray {
        val entriesStart = TYPE_HEADER + count * Int.SIZE_BYTES
        val chunk = buffer(entriesStart + valueIndexes.size * ENTRY_SIZE)
        chunk.putShort(0, 0x0201).putShort(2, TYPE_HEADER.toShort()).putInt(4, chunk.capacity())
        chunk.put(8, 1).putInt(12, count).putInt(16, entriesStart)
        chunk.putInt(20, CONFIG_SIZE)
        language.toByteArray().forEachIndexed { index, byte -> chunk.put(20 + 8 + index, byte) }
        for (slot in 0 until count) {
            chunk.putInt(TYPE_HEADER + slot * Int.SIZE_BYTES, if (slot < valueIndexes.size) slot * ENTRY_SIZE else -1)
        }
        valueIndexes.forEachIndexed { slot, value ->
            val entry = entriesStart + slot * ENTRY_SIZE
            chunk.putShort(entry, 8).putInt(entry + 4, slot)
            chunk.putShort(entry + 8, 8).put(entry + 11, 3).putInt(entry + 12, value)
        }
        return chunk.array()
    }

    /** A UTF-8 `ResStringPool`. */
    private fun pool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { string ->
            val offset = data.size()
            val bytes = string.toByteArray()
            data.write(string.length)
            data.write(bytes.size)
            data.write(bytes)
            data.write(0)
            offset
        }
        while (data.size() % Int.SIZE_BYTES != 0) data.write(0)
        val stringsStart = POOL_HEADER + strings.size * Int.SIZE_BYTES
        val chunk = buffer(stringsStart + data.size())
        chunk.putShort(0, 0x0001).putShort(2, POOL_HEADER.toShort()).putInt(4, chunk.capacity())
        chunk.putInt(8, strings.size).putInt(16, 1 shl 8).putInt(20, stringsStart)
        chunk.position(POOL_HEADER)
        offsets.forEach(chunk::putInt)
        chunk.put(data.toByteArray())
        return chunk.array()
    }

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private companion object {
        const val TABLE_HEADER = 12
        const val PACKAGE_HEADER = 288
        const val POOL_HEADER = 28
        const val CONFIG_SIZE = 48
        const val TYPE_HEADER = 20 + CONFIG_SIZE
        const val ENTRY_SIZE = 16
    }
}
