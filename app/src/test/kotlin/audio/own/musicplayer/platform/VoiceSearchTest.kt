package audio.own.musicplayer.platform

import audio.own.music.library.model.Album
import audio.own.music.library.model.Playlist
import audio.own.musicplayer.platform.VoiceSearch.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a spoken request asks for, in English and Czech, and which album or playlist it finds. */
class VoiceSearchTest {
    @Test
    fun `nothing said, or continue in either language, goes on listening`() {
        for (query in listOf(null, "", "continue listening", "Resume my music in own.music", "play", "Pokračuj v poslechu", "Pusť dál mou hudbu v own.music")) {
            assertEquals(query, Request.Continue, VoiceSearch.parse(query))
        }
    }

    @Test
    fun `my playlist and the album are understood, the app's name dropped, the name kept as said`() {
        assertEquals(Request.Playlist("Road Trip"), VoiceSearch.parse("Play my playlist Road Trip in own.music"))
        assertEquals(Request.Playlist("Výlet"), VoiceSearch.parse("Přehraj můj playlist Výlet v own.music"))
        assertEquals(Request.Album("Kind of Blue"), VoiceSearch.parse("play the album Kind of Blue on own music"))
        assertEquals(Request.Album("Kind of Blue"), VoiceSearch.parse("Přehraj album Kind of Blue"))
        assertEquals(Request.Anything("Kind of Blue"), VoiceSearch.parse("Listen to Kind of Blue"))
        assertEquals(Request.Anything("Kind of Blue"), VoiceSearch.parse("Pusť Kind of Blue v own.music"))
    }

    @Test
    fun `the system's focus says what a bare name is`() {
        assertEquals(Request.Album("Abbey Road"), VoiceSearch.parse("Abbey Road", VoiceSearch.Focus.ALBUM))
        assertEquals(Request.Playlist("gym"), VoiceSearch.parse("gym", VoiceSearch.Focus.PLAYLIST))
    }

    @Test
    fun `matching ignores accents, case and punctuation`() {
        assertEquals("cesky rozhlas jazz", VoiceSearch.fold("Český rozhlas: JAZZ!"))
        assertEquals(100, VoiceSearch.score("vylet", "Výlet"))
        assertTrue(VoiceSearch.score("vylet na hory", "Výlet") >= 80)
        assertEquals(0, VoiceSearch.score("gym", "Road Trip"))
    }

    @Test
    fun `the best playlist and album come first, unrelated ones not at all`() {
        val playlists = listOf(Playlist("s|1", "Road Trip Classics"), Playlist("s|2", "Road Trip"), Playlist("s|3", "Gym"))
        assertEquals(listOf("s|2", "s|1"), VoiceSearch.rankedPlaylists("road trip", playlists).map { it.first.id })
        val albums = listOf(Album("s|a", "Blue Train", artist = "John Coltrane"), Album("s|b", "Kind of Blue", artist = "Miles Davis"))
        assertEquals("s|b", VoiceSearch.rankedAlbums("kind of blue miles davis", albums).first().first.id)
        assertEquals("s|b", VoiceSearch.rankedAlbums("Kind of Blue", albums).first().first.id)
        assertTrue(VoiceSearch.rankedAlbums("symphony", albums).isEmpty())
    }
}
