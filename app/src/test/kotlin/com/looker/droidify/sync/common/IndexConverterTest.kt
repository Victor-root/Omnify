package com.looker.droidify.sync.common

import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.v1.model.IndexV1
import com.looker.droidify.sync.v2.model.RELEASE_CHANNEL_BETA
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexConverterTest {

    private fun indexWith(suggestedVersionCode: String?): IndexV1 {
        val suggested = suggestedVersionCode?.let { "\"suggestedVersionCode\": \"$it\"," }.orEmpty()
        return JsonParser.decodeFromString(
            """
            {
              "repo": {"address": "https://repo.example", "icon": "icon.png", "name": "Example",
                       "description": "", "timestamp": 1, "version": 21},
              "apps": [{"packageName": "org.example", $suggested "license": "GPL-3.0-only"}],
              "packages": {"org.example": [
                {"apkName": "a_30.apk", "hash": "h30", "hashType": "sha256", "packageName": "org.example",
                 "size": 1, "versionCode": 30, "versionName": "3.0-beta1"},
                {"apkName": "a_20.apk", "hash": "h20", "hashType": "sha256", "packageName": "org.example",
                 "size": 1, "versionCode": 20, "versionName": "2.0"},
                {"apkName": "a_10.apk", "hash": "h10", "hashType": "sha256", "packageName": "org.example",
                 "size": 1, "versionCode": 10, "versionName": "1.0"}
              ]}
            }
            """.trimIndent(),
        )
    }

    private fun IndexV1.channelsByVersionCode(): Map<Long, List<String>> =
        toV2().packages.getValue("org.example").versions.values
            .associate { it.manifest.versionCode to it.releaseChannels }

    @Test
    fun aVersionNewerThanTheRecommendedOneIsABeta() {
        assertEquals(
            mapOf(30L to listOf(RELEASE_CHANNEL_BETA), 20L to emptyList(), 10L to emptyList()),
            indexWith(suggestedVersionCode = "20").channelsByVersionCode(),
        )
    }

    @Test
    fun withoutARecommendedVersionNothingIsABeta() {
        assertEquals(
            mapOf(30L to emptyList<String>(), 20L to emptyList(), 10L to emptyList()),
            indexWith(suggestedVersionCode = null).channelsByVersionCode(),
        )
    }
}
