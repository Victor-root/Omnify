package com.looker.droidify.external

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny REST client for the external-source feature, covering GitHub, GitLab and Codeberg (Gitea).
 * Reuses the app's shared Ktor [HttpClient]. Unauthenticated, so it's subject to each provider's
 * anonymous rate limit — plenty for occasionally adding a source and checking a handful of apps.
 */
@Singleton
class ExternalApi @Inject constructor(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestReleaseFor(app: ExternalApp): Release? =
        latestRelease(app.provider, app.owner, app.repo, app.includePrereleases)

    /**
     * Fetches the release Droidify should offer for the project: the newest non-draft release
     * (optionally including pre-releases). Returns null on network/HTTP/parse failure or when the
     * project has no matching release.
     */
    suspend fun latestRelease(
        provider: SourceProvider,
        owner: String,
        repo: String,
        includePrereleases: Boolean = false,
    ): Release? = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                SourceProvider.GITHUB -> {
                    val text = getText(
                        url = "https://api.github.com/repos/$owner/$repo/releases?per_page=10",
                        github = true,
                    ) ?: return@runCatching null
                    decodeRest(text).firstMatching(includePrereleases)?.toRelease()
                }

                SourceProvider.CODEBERG -> {
                    val text = getText(
                        url = "https://codeberg.org/api/v1/repos/$owner/$repo/releases?limit=10",
                    ) ?: return@runCatching null
                    decodeRest(text).firstMatching(includePrereleases)?.toRelease()
                }

                SourceProvider.GITLAB -> {
                    val path = URLEncoder.encode("$owner/$repo", "UTF-8")
                    val text = getText(
                        url = "https://gitlab.com/api/v4/projects/$path/releases?per_page=10",
                    ) ?: return@runCatching null
                    decodeGitlab(text)
                        .firstOrNull { includePrereleases || !it.upcomingRelease }
                        ?.toRelease()
                }
            }
        }.getOrNull()
    }

    private fun decodeRest(text: String): List<RestReleaseDto> =
        json.decodeFromString(ListSerializer(RestReleaseDto.serializer()), text)

    private fun decodeGitlab(text: String): List<GitlabReleaseDto> =
        json.decodeFromString(ListSerializer(GitlabReleaseDto.serializer()), text)

    private fun List<RestReleaseDto>.firstMatching(includePrereleases: Boolean): RestReleaseDto? =
        firstOrNull { !it.draft && (includePrereleases || !it.prerelease) }

    private suspend fun getText(url: String, github: Boolean = false): String? {
        val response = httpClient.get(url) {
            if (github) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
        }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }
}
