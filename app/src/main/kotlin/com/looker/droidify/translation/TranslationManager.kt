package com.looker.droidify.translation

import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.TranslationEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates app-description text into the device language using whichever backend the user picked in
 * settings ([TranslationEngine]). Network engines reuse the app's shared Ktor client; the on-device
 * engine is delegated to [MlKitTranslator]. Suspends; throws on any failure (the caller turns that into
 * a friendly "couldn't translate" state). Nothing is contacted or downloaded until the user taps
 * Translate.
 */
@Singleton
class TranslationManager @Inject constructor(
    private val httpClient: HttpClient,
    private val settingsRepository: SettingsRepository,
    private val mlKitTranslator: MlKitTranslator,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** On-device detection of [text]'s language (a BCP-47 tag), independent of the chosen engine —
     *  drives the "translate automatically" decision. Null/"und" when unknown. */
    suspend fun detectLanguage(text: String): String? = mlKitTranslator.detectLanguage(text)

    /** Translates [text] into [targetLanguage] (an ISO-639-1 code such as "fr"). */
    suspend fun translate(text: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
        when (settingsRepository.getInitial().translationEngine) {
            TranslationEngine.NONE -> error("No translation engine selected")
            TranslationEngine.GOOGLE -> googleTranslate(text, targetLanguage)
            TranslationEngine.LIBRETRANSLATE -> {
                val settings = settingsRepository.getInitial()
                libreTranslate(
                    text = text,
                    target = targetLanguage,
                    baseUrl = settings.libreTranslateUrl,
                    apiKey = settings.libreTranslateApiKey,
                )
            }

            TranslationEngine.MLKIT -> mlKitTranslator.translate(text, targetLanguage)
        }
    }

    /** Google's free, unofficial endpoint. Returns the concatenated translated sentences. */
    private suspend fun googleTranslate(text: String, target: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=$target&dt=t&q=$encoded"
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "Translate request failed (${response.status.value})" }
        // The response is a nested array; [0] holds the translated sentences, each at [0].
        val sentences = json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonArray
        return buildString {
            sentences.forEach { append(it.jsonArray[0].jsonPrimitive.content) }
        }
    }

    /** A user-provided LibreTranslate instance. */
    private suspend fun libreTranslate(
        text: String,
        target: String,
        baseUrl: String,
        apiKey: String,
    ): String {
        require(baseUrl.isNotBlank()) { "No LibreTranslate instance configured" }
        val url = baseUrl.trim().trimEnd('/') + "/translate"
        val body = json.encodeToString(
            LibreRequest.serializer(),
            LibreRequest(
                q = text,
                source = "auto",
                target = target,
                format = "text",
                apiKey = apiKey.ifBlank { null },
            ),
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.isSuccess()) { "Translate request failed (${response.status.value})" }
        return json.decodeFromString(LibreResponse.serializer(), response.bodyAsText()).translatedText
    }

    @Serializable
    private data class LibreRequest(
        val q: String,
        val source: String,
        val target: String,
        val format: String,
        @SerialName("api_key") val apiKey: String? = null,
    )

    @Serializable
    private data class LibreResponse(val translatedText: String = "")
}
