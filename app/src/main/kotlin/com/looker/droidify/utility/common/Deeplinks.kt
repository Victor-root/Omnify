package com.looker.droidify.utility.common

import android.content.Intent
import com.looker.droidify.utility.common.extension.get

const val LEGACY_HOST = "droidify.eu.org"

const val PERSONAL_HOST = "droidify.app"

fun shareUrl(packageName: String, repoAddress: String) =
    "https://droidify.app/app/?id=$packageName&repo_address=$repoAddress"

private val httpScheme = arrayOf("http", "https")
private val fdroidRepoScheme = arrayOf("fdroidrepo", "fdroidrepos")

private val supportedExternalHosts = arrayOf(
    "f-droid.org",
    "www.f-droid.org",
    "staging.f-droid.org",
    "apt.izzysoft.de",
)

fun Intent.deeplinkType(): DeeplinkType? {
    val data = data ?: return null

    return when (data.scheme) {
        "package",
        "fdroid.app",
        -> {
            val packageName = data.schemeSpecificPart?.nullIfEmpty()
                ?: invalidDeeplink("Invalid packageName: $data")
            DeeplinkType.AppDetail(packageName)
        }

        in fdroidRepoScheme -> {
            val repoAddress = when (data.scheme) {
                "fdroidrepos" -> {
                    dataString!!.replaceFirst("fdroidrepos", "https")
                }
                "fdroidrepo" -> {
                    dataString!!.replaceFirst("fdroidrepo", "https")
                }
                else -> {
                    invalidDeeplink("No repo address: $data")
                }
            }

            DeeplinkType.AddRepository(repoAddress)
        }

        "market" if data.host == "details" -> {
            val packageName = data["id"]?.nullIfEmpty()
                ?: invalidDeeplink("Invalid packageName: $data")
            DeeplinkType.AppDetail(packageName)
        }

        "market" if data.host == "search" -> {
            val packageName = data["q"]?.nullIfEmpty()
                ?: invalidDeeplink("Invalid query: $data")
            DeeplinkType.AppSearch(packageName)
        }

        // omnify://add?url=<repo url>, behind the "Get it on Omnify" badge a project puts in its
        // README (see the site's add page, which is what the badge actually links to). The url is
        // handed on as text rather than parsed here: deciding whether it names a single repo or a
        // whole account, and which provider serves it, is parseExternalSource/parseAccountSource's
        // job, and the share-sheet route already calls exactly those. So a badge and a shared link
        // reach the same dialog by the same rules, and a shape one accepts can never be one the
        // other turns away.
        "omnify" if data.host == "add" -> {
            val sourceUrl = data["url"]?.nullIfEmpty()
                ?: invalidDeeplink("No source url: $data")
            DeeplinkType.AddExternalSource(sourceUrl)
        }

        in httpScheme -> {
            when (data.host) {
                PERSONAL_HOST,
                LEGACY_HOST,
                -> {
                    val repoAddress = data["repo_address"]
                    if (data.path == "/app/") {
                        val packageName = data["id"]?.nullIfEmpty()
                            ?: invalidDeeplink("Invalid packageName: $data")
                        DeeplinkType.AppDetail(packageName, repoAddress)
                    } else {
                        invalidDeeplink("Unknown intent path: ${data.path}, Data: $data")
                    }
                }

                in supportedExternalHosts -> {
                    val packageName = data.lastPathSegment?.nullIfEmpty()
                        ?: invalidDeeplink("Invalid packageName: $data")
                    DeeplinkType.AppDetail(packageName)
                }

                else -> null
            }
        }
        else -> null
    }
}

/**
 * The URL from a shared-text intent (ACTION_SEND, e.g. a browser's "Share link" on a repo page).
 * Browsers usually put just the URL in EXTRA_TEXT, but some prepend a title, so the first http(s)
 * token is taken; failing that, the trimmed text as-is (so a bare "owner/repo" still comes through).
 * Null when there's no shared text.
 */
fun Intent.sharedSourceUrl(): String? {
    val text = getStringExtra(Intent.EXTRA_TEXT)?.trim()?.nullIfEmpty() ?: return null
    val token = text.split(Regex("\\s+"))
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        ?: text
    // Drop any query/fragment (e.g. "?tab=readme", "#install") — never part of an owner/repo path.
    return token.substringBefore('?').substringBefore('#').nullIfEmpty()
}

class InvalidDeeplink(override val message: String?) : IllegalStateException(message)

@Suppress("NOTHING_TO_INLINE")
private inline fun invalidDeeplink(message: String): Nothing = throw InvalidDeeplink(message)

sealed interface DeeplinkType {

    class AddRepository(val address: String) : DeeplinkType

    class AppDetail(val packageName: String, val repoAddress: String? = null) : DeeplinkType

    class AppSearch(val query: String) : DeeplinkType

    /** A GitHub/GitLab/Codeberg/self-hosted project [url] to follow as an external source, from an
     *  `omnify://add` link. Whether it names a single repo or a whole account is decided where it's
     *  acted on, by the same functions the share sheet's own route uses. */
    class AddExternalSource(val url: String) : DeeplinkType
}
