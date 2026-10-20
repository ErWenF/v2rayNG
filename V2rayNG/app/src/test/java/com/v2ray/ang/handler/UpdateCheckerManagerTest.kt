package com.v2ray.ang.handler

import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.GitHubRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UpdateCheckerManagerTest {
    private val flavor = if (BuildConfig.APPLICATION_ID.contains("fdroid", ignoreCase = true)) "-fdroid" else ""
    private val otherFlavor = if (flavor.isEmpty()) "-fdroid" else ""

    @Test
    fun x86DoesNotMatchX86_64OrItsSignature() {
        val expected = "v2rayNG_2.3.11.1${flavor}_x86.apk"
        val release = release(
            "v2rayNG_2.3.11.1${flavor}_x86_64.apk",
            "$expected.sig",
            "$expected.sha256",
            expected
        )

        assertEquals(assetUrl(expected), UpdateCheckerManager.getDownloadUrl(release, "x86"))
    }

    @Test
    fun generatedAndPublishedNamesMatchAllSupportedAbis() {
        for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "universal")) {
            for (name in listOf("v2rayNG_2.3.11.1${flavor}_${abi}.apk", "v2rayNG-Po0-2.3.11.1${flavor}-${abi}.apk")) {
                assertEquals(assetUrl(name), UpdateCheckerManager.getDownloadUrl(release(name), abi))
            }
        }
    }

    @Test
    fun aMixedReleasePreservesTheInstalledDistribution() {
        val wrong = "v2rayNG_2.3.11.1${otherFlavor}_arm64-v8a.apk"
        val expected = "v2rayNG_2.3.11.1${flavor}_arm64-v8a.apk"

        assertEquals(
            assetUrl(expected),
            UpdateCheckerManager.getDownloadUrl(release(wrong, expected), "arm64-v8a")
        )
        assertThrows(IllegalStateException::class.java) {
            UpdateCheckerManager.getDownloadUrl(release(wrong), "arm64-v8a")
        }
    }

    @Test
    fun namesAreCaseInsensitiveAndRequireAnAbiSeparator() {
        val invalid = "v2rayNG_2.3.11.1${flavor}x86.apk"
        val expected = "v2rayNG_2.3.11.1${flavor}_X86.APK"

        assertEquals(assetUrl(expected), UpdateCheckerManager.getDownloadUrl(release(invalid, expected), "x86"))
        assertThrows(IllegalStateException::class.java) {
            UpdateCheckerManager.getDownloadUrl(release(invalid), "x86")
        }
    }

    @Test
    fun incompatibleApksAndAuxiliaryFilesDoNotBecomeDownloads() {
        assertThrows(IllegalStateException::class.java) {
            UpdateCheckerManager.getDownloadUrl(
                release(
                    "v2rayNG_2.3.11.1${flavor}_x86_64.apk",
                    "v2rayNG_2.3.11.1${flavor}_x86.apk.sig",
                    "v2rayNG_2.3.11.1${flavor}_x86.apk.sha256",
                    "SHA256SUMS.txt"
                ),
                "x86"
            )
        }
    }

    private fun release(vararg names: String) = GitHubRelease(
        tagName = "2.3.11.1",
        body = "",
        assets = names.map { GitHubRelease.Asset(it, assetUrl(it)) }
    )

    private fun assetUrl(name: String) = "https://github.com/ErWenF/v2rayNG/releases/download/2.3.11.1/$name"
}
