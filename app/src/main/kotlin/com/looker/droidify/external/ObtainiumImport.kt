package com.looker.droidify.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * What an Obtainium export turned into: the sources Omnify can follow, and the apps it cannot (the ones
 * Obtainium follows on a site that is not a GitHub, GitLab or Forgejo release page: F-Droid, APKMirror,
 * a plain web page...), by name, so the person knows what was left behind.
 */
data class ObtainiumImport(
    val apps: List<ExternalApp>,
    val unsupported: List<String>,
)

/**
 * Reads an Obtainium export ("Import/export" > "Obtainium export") into external sources, without any
 * network access: the releases, icons and names are then filled in by the regular refresh, as for a
 * restored backup.
 *
 * Every shape Obtainium has written is read: the current `{"schemaVersion", "apps": [...]}`, the older
 * `{"apps": [...]}`, and the oldest bare list. Each app's own settings are a JSON object stored as a
 * string, and are taken over where Omnify has the same thing:
 *  - `includePrereleases`, as is;
 *  - `apkFilterRegEx`, as the APK filter, unless `invertAPKFilter` turns it into "everything but",
 *    which Omnify cannot say and would turn into its opposite;
 *  - `filterReleaseTitlesByRegEx`, as the "versions to keep" filter when it is a plain word or a list
 *    of them (`beta|stable`), since that filter takes words, not patterns;
 *  - `appName`, the name the person gave the app, which stays theirs.
 * Anything else, `trackOnly` among it, has no counterpart and is left out.
 *
 * Obtainium can follow one repository several times, each time for another app told apart by those
 * settings (nullcpy/rvb carries a dozen apps in every release, each followed with its own APK filter).
 * Each becomes a source of its own, an [ExternalApp.variant] named after the app; the same repository
 * followed twice with the very same settings is one source.
 *
 * Throws [IllegalArgumentException] when the text is not an Obtainium export at all.
 */
fun parseObtainiumExport(text: String): ObtainiumImport {
    val root = runCatching { Json.parseToJsonElement(text) }
        .getOrElse { throw IllegalArgumentException("Not JSON", it) }
    val entries = when (root) {
        is JsonArray -> root
        is JsonObject -> root["apps"] as? JsonArray
        else -> null
    } ?: throw IllegalArgumentException("No app list")

    val apps = mutableListOf<ExternalApp>()
    val unsupported = mutableListOf<String>()
    entries.filterIsInstance<JsonObject>().forEach { entry ->
        val app = entry.toExternalApp()
        if (app == null) {
            unsupported += entry.string("name") ?: entry.string("url") ?: "?"
        } else if (apps.none { it.repoKey == app.repoKey && it.followsSameAppAs(app) }) {
            apps += app
        }
    }
    return ObtainiumImport(apps.withVariants(), unsupported)
}

/** Gives every app of a repository followed more than once a [ExternalApp.variant] of its own, from its
 *  name, numbered when two share one. */
private fun List<ExternalApp>.withVariants(): List<ExternalApp> {
    val shared = groupingBy { it.repoKey }.eachCount().filterValues { it > 1 }.keys
    val taken = mutableSetOf<String>()
    return map { app ->
        if (app.repoKey !in shared) return@map app
        app.withVariantNamed(app.label, taken).also { taken += it.key }
    }
}

/**
 * The app an Obtainium share link names, with the settings Obtainium follows it with, read by the same
 * rules as an app of an export ([parseObtainiumExport]). Three shapes:
 *  - `obtainium://app/<the app as JSON>`, what Obtainium's own "Share" gives;
 *  - the same wrapped in its web page, `https://apps.obtainium.imranr.dev/redirect?r=<that link>`, so it
 *    opens from anywhere;
 *  - `obtainium://add/<project address>`, an address alone.
 *
 * Null for anything else, or for an app Omnify can't follow.
 */
fun parseObtainiumLink(link: String): SourceDraft? {
    val trimmed = link.trim()
    val inner = if (trimmed.startsWith(OBTAINIUM_REDIRECT)) {
        trimmed.substringAfter('?').split('&')
            .firstOrNull { it.startsWith("r=") }
            ?.removePrefix("r=")
            ?.let(::percentDecoded)
            ?: return null
    } else {
        trimmed
    }
    inner.removePrefixOrNull(OBTAINIUM_ADD)?.let { address ->
        val url = percentDecoded(address).trim()
        return if (sourceRef(url, null)?.provider != null) SourceDraft(url = url) else null
    }
    val json = inner.removePrefixOrNull(OBTAINIUM_APP)
        ?.let { if (it.startsWith('{')) it else percentDecoded(it) }
        ?: return null
    val entry = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val app = entry.toExternalApp() ?: return null
    return SourceDraft(
        url = entry.string("url") ?: return null,
        name = if (app.nameOverridden) app.label else "",
        includePrereleases = app.includePrereleases,
        apkFilter = app.apkFilter.orEmpty(),
        versionIncludeFilter = app.versionIncludeFilter.orEmpty(),
    )
}

private const val OBTAINIUM_REDIRECT = "https://apps.obtainium.imranr.dev/redirect"
private const val OBTAINIUM_APP = "obtainium://app/"
private const val OBTAINIUM_ADD = "obtainium://add/"

private fun String.removePrefixOrNull(prefix: String): String? =
    if (startsWith(prefix)) removePrefix(prefix) else null

/** [text] with its `%XX` escapes decoded as UTF-8. Unlike form decoding, a `+` stays a `+`: it is part
 *  of many an APK filter (`v.+\.apk`), and turning it into a space would break the pattern. */
private fun percentDecoded(text: String): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val escape = if (text[i] == '%' && i + 2 < text.length) text.substring(i + 1, i + 3) else null
        if (escape != null && escape.all { it in HEX_DIGITS }) {
            bytes.write(escape.toInt(16))
            i += 3
        } else {
            val codePoint = text.codePointAt(i)
            bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(codePoint)
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private const val HEX_DIGITS = "0123456789abcdefABCDEF"

private fun JsonObject.toExternalApp(): ExternalApp? {
    val url = string("url") ?: return null
    val ref = sourceRef(url, string("overrideSource")) ?: return null
    val settings = settings()
    val customName = settings.string("appName")
    val name = string("name")
    return ExternalApp(
        provider = ref.provider ?: return null,
        host = ref.host,
        owner = ref.owner,
        repo = ref.repo,
        label = customName ?: name ?: prettifyRepoName(ref.repo),
        nameOverridden = customName != null,
        packageName = string("id")?.takeIf { it.isPackageName() },
        includePrereleases = settings.boolean("includePrereleases"),
        apkFilter = settings.string("apkFilterRegEx")?.takeUnless { settings.boolean("invertAPKFilter") },
        versionIncludeFilter = settings.string("filterReleaseTitlesByRegEx")?.toKeywords(),
    )
}

/**
 * Where the app comes from. Obtainium recognises GitHub, GitLab and Codeberg by their host; a
 * self-hosted GitLab or Forgejo is one it was told about, through [overrideSource], which names the
 * kind of site the address is. Anything else is not something Omnify follows.
 */
private fun sourceRef(url: String, overrideSource: String?): ExternalSourceRef? {
    val parsed = parseExternalSource(url) ?: return null
    if (overrideSource == null) return parsed.takeIf { it.provider != null }
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val segments = uri.path.orEmpty().substringBefore("/-/").split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    return when (overrideSource) {
        "GitHub" -> parsed.takeIf { it.provider == SourceProvider.GITHUB }
        // GitLab nests groups, so everything before the last segment is the owner.
        "GitLab" -> ExternalSourceRef(
            provider = SourceProvider.GITLAB,
            host = host.takeUnless { it == publicHost(SourceProvider.GITLAB) }.orEmpty(),
            owner = segments.dropLast(1).joinToString("/"),
            repo = segments.last().removeSuffix(".git"),
        )
        "Codeberg" -> ExternalSourceRef(
            provider = SourceProvider.CODEBERG,
            host = host.takeUnless { it == publicHost(SourceProvider.CODEBERG) }.orEmpty(),
            owner = segments[0],
            repo = segments[1].removeSuffix(".git"),
        )
        else -> null
    }
}

/** The app's own settings, a JSON object Obtainium stores as a string (or, read leniently, as is). */
private fun JsonObject.settings(): JsonObject = when (val raw = this["additionalSettings"]) {
    is JsonObject -> raw
    is JsonPrimitive -> raw.contentOrNull
        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
        ?: JsonObject(emptyMap())
    else -> JsonObject(emptyMap())
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

private fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/** A plain word, or plain words joined by `|`, as the comma-separated words a version filter takes.
 *  Null for any other pattern, which a word filter cannot reproduce. */
private fun String.toKeywords(): String? {
    val words = split('|').map { it.trim() }
    return words.takeIf { list -> list.all { it.matches(PLAIN_WORD) } }?.joinToString(",")
}

private val PLAIN_WORD = Regex("[A-Za-z0-9_-]+")

/** A real Android package name, as opposed to the placeholder ids Obtainium gives an app it has not
 *  installed yet (digits, or 12 hex characters), which contain no dot. */
private fun String.isPackageName(): Boolean = matches(PACKAGE_NAME)

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
