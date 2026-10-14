package com.looker.droidify.external

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

    val apps = LinkedHashMap<String, ExternalApp>()
    val unsupported = mutableListOf<String>()
    entries.filterIsInstance<JsonObject>().forEach { entry ->
        val app = entry.toExternalApp()
        if (app == null) {
            unsupported += entry.string("name") ?: entry.string("url") ?: "?"
        } else {
            apps.putIfAbsent(app.key, app)
        }
    }
    return ObtainiumImport(apps.values.toList(), unsupported)
}

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
