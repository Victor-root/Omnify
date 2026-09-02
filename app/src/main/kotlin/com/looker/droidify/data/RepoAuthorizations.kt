package com.looker.droidify.data

/**
 * The `Authorization` header the repository [url] belongs to expects, or null when it belongs to none
 * of them. Keyed by repository address, as [RepoRepository.authorizations] hands them over.
 *
 * Matched on the whole address, path and all, never on the host alone: one server can hold a private
 * repository beside a public one, and a login belongs to exactly the repository it was entered for.
 * Nothing outside that repository's own path is given it.
 */
internal fun Map<String, String>.authorizationFor(url: String): String? = entries
    .firstOrNull { (address, _) -> url.startsWith(address.removeSuffix("/") + "/") }
    ?.value
