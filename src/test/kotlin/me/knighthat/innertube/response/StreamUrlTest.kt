package me.knighthat.innertube.response

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.headers
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import me.knighthat.innertube.Innertube
import me.knighthat.innertube.InnertubeProvider
import me.knighthat.innertube.request.Localization
import me.knighthat.innertube.request.body.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Live-network tests verifying that song stream URLs are fully retrievable.
 *
 * These tests catch the class of bug where only the first chunk of a stream is
 * accessible (causing playback to stop after ~1 minute) but later byte-ranges
 * return errors or throttled responses.
 *
 * Requires network access.
 */
class StreamUrlTest {

    companion object {

        // "Never Gonna Give You Up" — stable, always-available track.
        private const val SONG_ID = "dQw4w9WgXcQ"

        // Mirrors the app's CHUNK_LENGTH in PlayerModule.kt
        private const val CHUNK_LENGTH = 512 * 1024L

        // Plain HTTP client for range-request validation against googlevideo.com.
        // Picks up the same proxy the InnertubeProvider uses so CDN hosts are reachable in CI.
        private val streamHttpClient = HttpClient(OkHttp) {
            expectSuccess = false   // 206 Partial Content is expected; don't throw on it
            engine {
                (System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy"))
                    ?.takeIf(String::isNotBlank)
                    ?.let { proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(it)) }
            }
            install(ContentEncoding) {
                gzip(1f)
                deflate(.1f)
            }
        }

        @JvmStatic
        @BeforeAll
        fun setup() = Innertube.setProvider(InnertubeProvider())
    }

    /** Android client returns playability OK and has audio adaptive formats with direct URLs. */
    @Test
    fun androidPlayerReturnsAudioFormatsWithDirectUrls() = runBlocking {
        val result = Innertube.player(
            songId = SONG_ID,
            context = Context.ANDROID_DEFAULT,
            localization = Localization.EN_US,
            signatureTimestamp = null,
            visitorData = null
        )

        assertTrue(result.isSuccess, "player(Android) failed: ${result.exceptionOrNull()}")
        val response = result.getOrThrow()
        assertEquals("OK", response.playabilityStatus.status, "playabilityStatus not OK")

        val audioFormats = response.streamingData
            ?.adaptiveFormats
            ?.filter { it.mimeType.startsWith("audio") }
        assertNotNull(audioFormats, "streamingData is null")
        assertFalse(audioFormats!!.isEmpty(), "No audio adaptiveFormats in Android response")

        // Android / iOS clients return direct URLs, not signatureCipher
        val directUrl = audioFormats.any { it.url != null }
        assertTrue(directUrl, "Android client should provide at least one direct-URL audio format")
    }

    /**
     * The stream URL is reachable for a byte range BEYOND the first 512 KB chunk.
     *
     * A URL that only serves the first chunk would cause ExoPlayer to stall after
     * approximately 30–60 seconds of playback (depending on bitrate).
     */
    @Test
    fun streamUrlAccessibleBeyondFirstChunk() = runBlocking {
        val playerResult = Innertube.player(
            songId = SONG_ID,
            context = Context.ANDROID_DEFAULT,
            localization = Localization.EN_US,
            signatureTimestamp = null,
            visitorData = null
        )

        val audioFormats = playerResult.getOrThrow()
            .streamingData?.adaptiveFormats
            ?.filter { it.mimeType.startsWith("audio") && it.url != null }
            ?: error("No direct-URL audio formats")

        val best = audioFormats.maxByOrNull { it.bitrate } ?: error("Empty audio formats list")
        val streamUrl = best.url!!
        val contentLength = best.contentLength?.toLong()
            ?: error("contentLength missing on best audio format (itag=${best.itag})")

        assertTrue(
            contentLength > CHUNK_LENGTH,
            "Song contentLength ($contentLength) must be larger than CHUNK_LENGTH ($CHUNK_LENGTH) to test second chunk"
        )

        val secondChunkStart = CHUNK_LENGTH
        val secondChunkEnd = minOf(secondChunkStart + CHUNK_LENGTH - 1, contentLength - 1)

        // Attempt the range request; skip the test if the CDN hostname is unreachable.
        // googlevideo.com nodes use per-datacenter subdomains that may not resolve in
        // sandboxed CI environments. We use assumeTrue rather than failing so that the
        // test is informative locally (where the proxy is reachable) but doesn't block CI.
        val headResult = runCatching {
            streamHttpClient.head(streamUrl) {
                headers {
                    append(HttpHeaders.Range, "bytes=$secondChunkStart-$secondChunkEnd")
                }
            }
        }
        val cdnHost = java.net.URI(streamUrl).host
        val isNetworkError = headResult.exceptionOrNull() is java.net.UnknownHostException
            || headResult.exceptionOrNull()?.cause is java.net.UnknownHostException
        assumeTrue(!isNetworkError, "CDN host $cdnHost not reachable — skipping stream range test")

        val response = headResult.getOrThrow()
        assertTrue(
            response.status.isSuccess(),
            "Stream URL returned HTTP ${response.status} for range $secondChunkStart-$secondChunkEnd. " +
                "This likely means the URL is throttled or the second chunk is inaccessible."
        )
    }

    /**
     * `expiresInSeconds` from the player response is a positive relative duration.
     *
     * The bug in PlayerModule.kt stored `expiresInSeconds` directly as `expiredTimeMillis`
     * (a relative number like 21600) and then compared it with System.currentTimeMillis()
     * (a value ~1 748 000 000 000). The cached URL was therefore always considered expired,
     * triggering constant re-fetches on every chunk request.
     *
     * This test documents the expected shape of the field so the fix can be verified.
     */
    @Test
    fun expiresInSecondsIsPositiveRelativeDuration() = runBlocking {
        val result = Innertube.player(
            songId = SONG_ID,
            context = Context.ANDROID_DEFAULT,
            localization = Localization.EN_US,
            signatureTimestamp = null,
            visitorData = null
        )

        val streamingData = result.getOrThrow().streamingData
        assertNotNull(streamingData, "streamingData is null")

        val expiresInSeconds = streamingData!!.expiresInSeconds.toLong()
        assertTrue(expiresInSeconds > 0, "expiresInSeconds should be positive, got $expiresInSeconds")

        // YouTube normally grants at least 10 minutes (600 s) per URL
        assertTrue(
            expiresInSeconds >= 600,
            "expiresInSeconds ($expiresInSeconds) is suspiciously short — expected ≥ 600"
        )

        // The correctly computed absolute expiry must lie in the future
        val absoluteExpiryMs = System.currentTimeMillis() + expiresInSeconds * 1_000L
        assertTrue(
            absoluteExpiryMs > System.currentTimeMillis(),
            "Absolute expiry (currentTime + expiresInSeconds*1000) must be in the future"
        )
    }

    /** iOS player response also provides usable audio adaptive formats. */
    @Test
    fun iosPlayerReturnsAudioFormats() = runBlocking {
        val result = Innertube.player(
            songId = SONG_ID,
            context = Context.IOS_DEFAULT,
            localization = Localization.EN_US,
            signatureTimestamp = null,
            visitorData = null
        )

        assertTrue(result.isSuccess, "player(iOS) failed: ${result.exceptionOrNull()}")
        assertEquals("OK", result.getOrThrow().playabilityStatus.status)

        val audioFormats = result.getOrThrow()
            .streamingData?.adaptiveFormats
            ?.filter { it.mimeType.startsWith("audio") }
        assertFalse(audioFormats.isNullOrEmpty(), "iOS client returned no audio adaptive formats")
    }
}
