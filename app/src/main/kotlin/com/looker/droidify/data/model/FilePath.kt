package com.looker.droidify.data.model

import androidx.compose.runtime.Immutable

@JvmInline
@Immutable
value class FilePath(val path: String)

fun FilePath(
    baseUrl: String,
    path: String?,
): FilePath? {
    if (path.isNullOrBlank()) return null
    // Join base + path with exactly one separator. Built directly (no shared StringBuilder):
    // this runs concurrently from several DB-mapping threads, and a cached, mutable builder
    // shared per baseUrl raced and crashed with StringIndexOutOfBoundsException.
    val joined = when {
        !baseUrl.endsWith("/") && !path.startsWith("/") -> "$baseUrl/$path"
        baseUrl.endsWith("/") && path.startsWith("/") -> baseUrl.dropLast(1) + path
        else -> baseUrl + path
    }
    return FilePath(joined)
}
