package audio.own.music.library

import audio.own.kit.ownaudio.Credit
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.kit.ownaudio.Page
import audio.own.kit.ownaudio.Playlist as ApiPlaylist
import audio.own.kit.ownaudio.Track as ApiTrack
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.Sharing
import audio.own.music.library.ownaudio.ListenerState
import audio.own.music.library.ownaudio.OwnAudioSource
import audio.own.music.library.ownaudio.RecordMapper
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from music-ios MappingTests: the parts about own.audio and the shared models. The
 * Subsonic and Jellyfin cases come with their sources (M7, M8).
 */
class MappingTest {
    private fun credit(id: String, role: String = "artist", join: String = "", creditedAs: String? = null) =
        Credit(contributorId = id, role = role, joinPhrase = join, creditedAs = creditedAs)

    @Test fun `an artist image URL with a size parameter is asked for at the size drawn`() {
        val artist = Artist(id = "a", name = "A", imageUrl = "https://music.example.com/img/a?size=300&x=1")
        assertEquals("https://music.example.com/img/a?size=120&x=1", artist.imageUrl(120))
        val plain = Artist(id = "b", name = "B", imageUrl = "https://img.example.com/b.jpg")
        assertEquals("https://img.example.com/b.jpg", plain.imageUrl(120))
    }

    @Test fun `errors become the shared ones`() {
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.SignInRefused) is SourceError.SignInRejected)
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.Problem(401, "unauthorized")) is SourceError.SignInRejected)
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.Problem(403, "forbidden")) is SourceError.NotAllowed)
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.Problem(404, "not_found")) is SourceError.NotFound)
        assertEquals("Too big", (OwnAudioSource.sourceError(OwnAudioException.Problem(400, "invalid_body", "Too big")) as SourceError.Server).text)
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.Offline) is SourceError.Offline)
        assertTrue(OwnAudioSource.sourceError(OwnAudioException.Network(IOException())) is SourceError.Network)
    }

    @Test fun `a credit line - names in order, each with what joins it to the next, as credited`() {
        val names = mapOf("a" to "Alice", "b" to "Bob", "c" to "Carol").let { m -> { id: String -> m[id] } }
        val credits = listOf(credit("a", join = " & "), credit("b", join = " feat. "), credit("c", role = "featured"))
        assertEquals("Alice & Bob feat. Carol", RecordMapper.creditLine(credits, RecordMapper.TRACK_ROLES, names))
        assertEquals("the album line leaves the guest out, and the dangling ' feat. '", "Alice & Bob", RecordMapper.creditLine(credits, RecordMapper.ALBUM_ROLES, names))
        assertEquals("Prince", RecordMapper.creditLine(listOf(credit("a", creditedAs = "Prince")), RecordMapper.ALBUM_ROLES, names))
        assertNull(RecordMapper.creditLine(listOf(credit("a", role = "narrator")), RecordMapper.ALBUM_ROLES, names))
    }

    @Test fun `a cover id changes with the image, and names its record`() {
        assertEquals("s|al~o9", RecordMapper.coverId("s", "al", "https://h/api/v2/images/o9?e=1&s=x"))
        assertEquals("not an own.audio image link: the record alone", "s|al", RecordMapper.coverId("s", "al", "https://x"))
        assertNull(RecordMapper.coverId("s", "al", null))
        assertEquals("s" to "al", RecordMapper.recordOfCover("s|al~o9"))
        assertEquals(null to "al", RecordMapper.recordOfCover("al"))
        assertEquals("a song without its own cover shows its album's", "s|al~o9", RecordMapper.trackCoverId("s", "t", null, "al", "https://h/api/v2/images/o9"))
    }

    @Test fun `whose a song is - unknown until the account is, private is not locked`() {
        assertNull(RecordMapper.sharing("u", "private", null))
        assertEquals(Sharing.MINE, RecordMapper.sharing("u", "private", "u"))
        assertEquals(Sharing.SHARED, RecordMapper.sharing("u", "family", "u"))
        assertEquals(Sharing.HOUSEHOLD, RecordMapper.sharing("other", "family", "u"))
        assertTrue(Sharing.SHARED.isMine)
        assertFalse(Sharing.HOUSEHOLD.isMine)
    }

    @Test fun `formats, bitrates and years`() {
        assertEquals("flac", RecordMapper.suffix("audio/x-flac"))
        assertEquals("opus", RecordMapper.suffix("audio/opus"))
        assertNull(RecordMapper.suffix("application/octet-stream"))
        assertEquals(800_000, RecordMapper.bitRate(20_000_000, 200_000))
        assertNull(RecordMapper.bitRate(0, 200_000))
        assertEquals(2001, RecordMapper.year("2001-05-01"))
        assertNull(RecordMapper.year(null))
    }

    @Test fun `a recorded track answer maps - composite ids, credits, the album from included`() {
        val page = OwnAudioJson.decodeFromString(Page.serializer(ApiTrack.serializer()), answer("tracks"))
        val listener = ListenerState.Values(favourites = setOf(page.items.first().id), userId = "06508aa1-7e3a-4e45-8764-08c1b37e8d74")
        val mapper = RecordMapper("s", page.included, listener)
        val song = mapper.song(page.items.first())
        assertEquals("s|${page.items.first().id}", song.id)
        assertEquals("s|${page.items.first().albumId}", song.albumId)
        assertTrue(song.isFavorite)
        assertEquals(Sharing.SHARED, song.sharing)
        assertEquals("mp3", song.suffix)
        assertTrue(song.coverArt!!.startsWith("s|${page.items.first().id}~"))
        page.included?.albums?.firstOrNull { it.id == page.items.first().albumId }?.let { assertEquals(it.title, song.album) }
        page.included?.contributors?.firstOrNull()?.let { c -> if (page.items.first().credits.first().contributorId == c.id) assertEquals(c.name, song.artist) }
    }

    @Test fun `a playlist is the account's own by owner id`() {
        val p = OwnAudioJson.decodeFromString(ApiPlaylist.serializer(), playlistJson("p1", "Mine"))
        val mine = RecordMapper("s", listener = ListenerState.Values(userId = "u")).playlist(p, "me@example.com")
        assertEquals("me@example.com", mine.owner)
        assertEquals(true, mine.isMine)
        val theirs = RecordMapper("s", listener = ListenerState.Values(userId = "x")).playlist(p, "me@example.com", mapOf("u" to "Mum"))
        assertEquals("u", theirs.owner)
        assertEquals("Mum", theirs.ownerName)
        assertEquals(false, theirs.isMine)
        assertNull(RecordMapper("s").playlist(p, "me").isMine)
    }

    @Test fun `synced lyrics highlight the line reached, with the offset`() {
        val lyrics = Lyrics(listOf(Lyrics.Line(0, "a"), Lyrics.Line(5_000, "b"), Lyrics.Line(9_000, "c")), isSynced = true, offsetMs = 1_000)
        assertEquals(0, lyrics.lineIndex(5_500))
        assertEquals(1, lyrics.lineIndex(6_000))
        assertEquals(2, lyrics.lineIndex(60_000))
        assertNull(Lyrics(lyrics.lines, isSynced = false).lineIndex(6_000))
        assertNull(Lyrics(listOf(Lyrics.Line(5_000, "x")), isSynced = true).lineIndex(1_000))
    }
}
