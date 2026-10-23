package com.looker.droidify.translation

import android.util.Log
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.translation.bergamot.BergamotTranslator
import com.looker.droidify.translation.bergamot.ENGLISH
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale
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
    private val bergamotTranslator: BergamotTranslator,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val _modelPreparation = MutableStateFlow<ModelPreparation?>(null)

    /** The chosen on-device engine's model being got ready ([prepare]), null when there is nothing to
     *  get or it hasn't been asked for. */
    val modelPreparation: StateFlow<ModelPreparation?> = _modelPreparation

    private var preparation: Job? = null

    /**
     * Downloads [engine]'s model for English to the device language as soon as the engine is picked,
     * rather than on the first translation, which then had to wait for it. English, because it is what
     * most app pages are written in. In the app's own scope, so leaving Settings doesn't stop it. Nothing
     * to get for an online engine, or on a device in English.
     */
    fun prepare(engine: TranslationEngine) {
        preparation?.cancel()
        val target = Locale.getDefault().language
        val onDevice = engine == TranslationEngine.MLKIT || engine == TranslationEngine.BERGAMOT
        if (!onDevice || target == ENGLISH) {
            _modelPreparation.value = null
            return
        }
        preparation = appScope.launch {
            _modelPreparation.value = ModelPreparation.DOWNLOADING
            _modelPreparation.value = try {
                if (engine == TranslationEngine.MLKIT) {
                    mlKitTranslator.prepare(target)
                } else {
                    bergamotTranslator.prepare(target)
                }
                ModelPreparation.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                ModelPreparation.FAILED
            }
        }
    }

    /** On-device detection of [text]'s language (a BCP-47 tag): drives the "translate automatically"
     *  decision. ML Kit's whatever the engine, except with Mozilla's, which brings its own so that
     *  nothing of Google runs. Null/"und" when unknown. */
    suspend fun detectLanguage(text: String): String? =
        if (settingsRepository.getInitial().translationEngine == TranslationEngine.BERGAMOT) {
            bergamotTranslator.detectLanguage(text)
        } else {
            mlKitTranslator.detectLanguage(text)
        }

    /** [html] translated whole, tags kept in place by the engine itself, or null when the chosen engine
     *  can't (see [translateHtml], which cuts it into pieces then). */
    suspend fun translateWholeHtml(html: String, targetLanguage: String, sourceLanguage: String?): String? =
        if (settingsRepository.getInitial().translationEngine == TranslationEngine.BERGAMOT) {
            bergamotTranslator.translate(html, targetLanguage, sourceLanguage, isHtml = true)
        } else {
            null
        }

    /**
     * Translates [text] into [targetLanguage] (an ISO-639-1 code such as "fr"), holding back the emoji
     * that open and close a line so no engine can eat them (see [translatePreservingSymbols]).
     *
     * [sourceLanguage] is for callers translating one document in several pieces: detect it once over
     * the whole thing and pass it to every piece. The network engines detect server side and ignore it;
     * the on-device one needs it (see [MlKitTranslator.translate]). Null leaves each engine to work the
     * source language out for itself.
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String? = null,
    ): String = withContext(Dispatchers.IO) {
        translatePreservingSymbols(text) { translateWithEngine(it, targetLanguage, sourceLanguage) }
    }

    /**
     * Whether the chosen engine returns a text with its line breaks where they were, which lets
     * [translateHtml] send many pieces of a page in one request. ML Kit's on-device engine joins every
     * line into one (measured on a SHIELD TV, five batches out of five).
     */
    suspend fun keepsLineBreaks(): Boolean =
        settingsRepository.getInitial().translationEngine != TranslationEngine.MLKIT

    private suspend fun translateWithEngine(
        text: String,
        targetLanguage: String,
        sourceLanguage: String?,
    ): String =
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

            TranslationEngine.MLKIT -> mlKitTranslator.translate(text, targetLanguage, sourceLanguage)
            TranslationEngine.BERGAMOT ->
                bergamotTranslator.translate(text, targetLanguage, sourceLanguage, isHtml = false)
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

private const val TAG = "TranslationManager"
