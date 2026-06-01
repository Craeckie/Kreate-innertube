package me.knighthat.innertube.response

import kotlinx.coroutines.runBlocking
import me.knighthat.innertube.Innertube
import me.knighthat.innertube.InnertubeProvider
import me.knighthat.innertube.SearchFilter
import me.knighthat.innertube.request.Localization
import me.knighthat.innertube.request.body.Context
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Live-network smoke tests that verify fetching songs from YouTube / YouTube Music
 * actually works end-to-end through the current [Innertube] API.
 *
 * These hit the real YouTube Music endpoints, so they require network access.
 * Each test asserts the call succeeds and returns usable data.
 */
class InnertubeImplTest {

    companion object {

        // A stable, always-available track ("Never Gonna Give You Up").
        private const val SONG_ID = "dQw4w9WgXcQ"

        @JvmStatic
        @BeforeAll
        fun setup() = Innertube.setProvider( InnertubeProvider() )
    }

    @Test
    fun searchReturnsResults() = runBlocking {
        val result = Innertube.search( Localization.EN_US, "million dollar baby", SearchFilter.SONGS )

        assertTrue( result.isSuccess, "search failed: ${result.exceptionOrNull()}" )
        assertFalse( result.getOrThrow().items.isEmpty(), "search returned no items" )
    }

    @Test
    fun searchSuggestionSucceeds() = runBlocking {
        val result = Innertube.searchSuggestion( Localization.EN_US, "million dollar baby" )

        assertTrue( result.isSuccess, "searchSuggestion failed: ${result.exceptionOrNull()}" )
    }

    @Test
    fun songBasicInfoReturnsSong() = runBlocking {
        val result = Innertube.songBasicInfo( SONG_ID, Localization.EN_US )

        assertTrue( result.isSuccess, "songBasicInfo failed: ${result.exceptionOrNull()}" )
        val song = result.getOrThrow()
        assertFalse( song.id.isBlank(), "song id is blank" )
        assertFalse( song.name.isBlank(), "song name is blank" )
    }

    @Test
    fun playerReturnsPlayableSong() = runBlocking {
        val result = Innertube.player(
            songId = SONG_ID,
            context = Context.WEB_REMIX_DEFAULT,
            localization = Localization.EN_US,
            signatureTimestamp = null,
            visitorData = null
        )

        assertTrue( result.isSuccess, "player failed: ${result.exceptionOrNull()}" )
    }
}
