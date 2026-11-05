package com.looker.droidify.utility.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DexTextTest {

    private val french = listOf(
        "Aucun passage prévu pour le moment sur cette ligne",
        "Ajoutez une gare ou un arrêt pour voir les prochains départs",
        "Les horaires ne sont pas disponibles pour le moment",
        "Vous pouvez choisir une direction dans la liste des lignes",
        "La clé est nécessaire pour avoir les données de la ligne",
    )

    @Test
    fun `the sentences of a dex file tell its language`() {
        assertEquals("fr", DexText.language(dexOf(french + listOf("Lcom/example/Main;", "api_key"))))
    }

    @Test
    fun `class names and keys alone tell nothing`() {
        assertNull(DexText.language(dexOf(listOf("Lcom/example/Main;", "api_key", "onCreate"))))
    }

    @Test
    fun `what is not a dex file is no answer`() {
        assertNull(DexText.language(ByteArray(256)))
        assertNull(DexText.language(ByteArray(0)))
    }

    @Test
    fun `a string table running past the end of the file is no answer`() {
        val dex = dexOf(french).copyOf(130)
        assertNull(DexText.language(dex))
    }

    /** A dex file down to what is read of it: the magic, the string table, and the strings. */
    private fun dexOf(strings: List<String>): ByteArray {
        val headerSize = 112
        val data = ByteArrayOutputStream()
        val tableEnd = headerSize + strings.size * Int.SIZE_BYTES
        val offsets = strings.map { string ->
            val offset = tableEnd + data.size()
            data.write(string.length.coerceAtMost(0x7F))
            data.write(string.toByteArray(Charsets.UTF_8))
            data.write(0)
            offset
        }
        val dex = ByteBuffer.allocate(tableEnd + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        dex.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        dex.putInt(56, strings.size)
        dex.putInt(60, headerSize)
        dex.position(headerSize)
        offsets.forEach(dex::putInt)
        dex.put(data.toByteArray())
        return dex.array()
    }
}
