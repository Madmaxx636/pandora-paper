package app.einkradio

import app.einkradio.pandora.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackTest {
    private fun track(id: String, artist: String, title: String) =
        Track(id = id, title = title, artist = artist, album = "", artUrl = null, artUrls = emptyList(), audioUrl = "")

    @Test fun ratingKeyIgnoresCaseAndSpaces() =
        assertEquals(track("a", "Frank Sinatra", "Winter Wonderland").ratingKey, track("b", " frank sinatra ", "WINTER WONDERLAND ").ratingKey)

    @Test fun ratingKeyDiffersBySong() =
        assertNotEquals(track("a", "Bing Crosby", "White Christmas").ratingKey, track("a", "Bing Crosby", "Silent Night").ratingKey)

    @Test fun pandoraTokenStripsReplayPrefix() =
        assertEquals("abc123", track("replay:2:abc123", "A", "B").pandoraToken)

    @Test fun pandoraTokenOfNormalTrackIsItsId() =
        assertEquals("abc123", track("abc123", "A", "B").pandoraToken)
}
