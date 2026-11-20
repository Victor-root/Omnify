package com.looker.droidify.utility.common

import com.looker.droidify.data.local.model.LocalizedAppNameEntity
import com.looker.droidify.data.local.model.findLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogueLanguageTest {

    private val spellings = listOf("en-US", "fr", "fr-CA", "fr-FR", "fr_FR", "fil", "fi-FI", "pt-BR", "pt-PT", "zh-CN", "zh-TW")

    @Test
    fun `the user's own region comes first, then the language alone, then its other regions`() {
        assertEquals(",fr-FR,fr_FR,fr,fr-CA,", CatalogueLanguage("fr-rFR").ranked(spellings))
        assertEquals(",fr,fr-CA,fr-FR,fr_FR,", CatalogueLanguage("fr").ranked(spellings))
    }

    @Test
    fun `regions that read differently are told apart`() {
        assertEquals(",pt-PT,pt-BR,", CatalogueLanguage("pt-rPT").ranked(spellings))
        assertEquals(",zh-TW,zh-CN,", CatalogueLanguage("zh-rTW").ranked(spellings))
    }

    @Test
    fun `a language is not taken for a longer one starting with it`() {
        assertEquals(",fi-FI,", CatalogueLanguage("fi").ranked(spellings))
    }

    @Test
    fun `an app's page picks the same translation as the lists`() {
        val names = listOf("en-US" to "Coffee", "fr-CA" to "Café (CA)", "fr-FR" to "Café")
            .map { (locale, name) -> LocalizedAppNameEntity(appId = 1, locale = locale, name = name) }
        assertEquals("Café", names.findLocale("fr-rFR").name)
        assertEquals("Café (CA)", names.findLocale("fr").name)
        assertEquals("Coffee", names.findLocale("de").name)
    }
}
