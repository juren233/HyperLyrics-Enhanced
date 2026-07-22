package com.juren233.hyperlyricsenhanced.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

data class ChangelogItem(
    val version: String,
    val title: String,
    val summary: String
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

object ChangelogData {
    const val ORIGINAL_REPOSITORY_URL = "https://github.com/limczhh/HyperLyric"

    private const val RELEASES_API =
        "https://api.github.com/repos/juren233/HyperLyrics-Enhanced/releases"
    private const val PAGE_SIZE = 100
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchChangelog(
        currentVersionName: String
    ): List<ChangelogItem> = withContext(Dispatchers.IO) {
        fetchReleases()
            .asSequence()
            .filterNot { it.draft || it.prerelease }
            .filter { isReleaseVisible(it.tagName, currentVersionName) }
            .map { release ->
                ChangelogItem(
                    version = release.tagName,
                    title = release.name.orEmpty().takeUnless { it == release.tagName }.orEmpty(),
                    summary = normalizeReleaseMarkdown(release.body.orEmpty())
                )
            }
            .toList()
    }

    private fun fetchReleases(): List<GitHubRelease> {
        val releases = mutableListOf<GitHubRelease>()
        var page = 1

        while (true) {
            val connection = URL("$RELEASES_API?per_page=$PAGE_SIZE&page=$page")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HyperLyrics-Enhanced-Android")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("GitHub Releases request failed: HTTP $responseCode")
                }

                val pageItems = connection.inputStream.bufferedReader().use { reader ->
                    json.decodeFromString<List<GitHubRelease>>(reader.readText())
                }
                releases += pageItems
                if (pageItems.size < PAGE_SIZE) break
                page++
            } finally {
                connection.disconnect()
            }
        }

        return releases
    }

    internal fun isReleaseVisible(
        tagName: String,
        currentVersionName: String
    ): Boolean {
        val parsed = parseReleaseVersion(tagName) ?: return false
        val currentParts = parseVersionParts(currentVersionName) ?: return false
        return compareVersionParts(parsed, currentParts) <= 0
    }

    internal fun normalizeReleaseMarkdown(markdown: String): String = markdown
        .replace(DETAILS_OPEN_REGEX, "")
        .replace(DETAILS_SUMMARY_REGEX) { match ->
            "\n### ${match.groupValues[1].trim()}\n"
        }
        .replace(DETAILS_CLOSE_REGEX, "")
        .replace(EXCESS_BLANK_LINES_REGEX, "\n\n")
        .trim()

    private fun parseReleaseVersion(tagName: String): List<Int>? {
        val match = Regex("^v(\\d+\\.\\d+\\.\\d+)$").matchEntire(tagName.trim())
            ?: return null
        return parseVersionParts(match.groupValues[1])
    }

    private fun parseVersionParts(versionName: String): List<Int>? {
        val normalized = versionName.trim().substringBefore('-').substringBefore('+')
        if (!normalized.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return null
        return normalized.split('.').mapNotNull(String::toIntOrNull)
    }

    private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
        val size = maxOf(left.size, right.size)
        repeat(size) { index ->
            val comparison = (left.getOrNull(index) ?: 0)
                .compareTo(right.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private val DETAILS_OPEN_REGEX = Regex("<details(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DETAILS_SUMMARY_REGEX = Regex(
        "<summary(?:\\s[^>]*)?>(.*?)</summary>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DETAILS_CLOSE_REGEX = Regex("</details\\s*>", RegexOption.IGNORE_CASE)
    private val EXCESS_BLANK_LINES_REGEX = Regex("\\n[\\t ]*\\n(?:[\\t ]*\\n)+")
}
