package audio.own.musicplayer.platform

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget's and the launcher's links, there and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicLinksTest {
    @Test
    fun `resume and player`() {
        assertEquals(MusicLinks.Link.Resume, MusicLinks.parse(MusicLinks.resume()))
        assertEquals(MusicLinks.Link.Player, MusicLinks.parse(Uri.parse("ownmusic://player")))
        assertEquals("ownmusic://resume", MusicLinks.resume().toString())
    }

    @Test
    fun `an album or playlist id with its server and odd characters survives the trip`() {
        val id = "0b6f-41|al/bum id#1?x"
        val link = MusicLinks.play(MusicLinks.Kind.ALBUM, id)
        assertEquals(MusicLinks.Link.Play(MusicLinks.Kind.ALBUM, id), MusicLinks.parse(link))
        assertEquals(MusicLinks.Link.Play(MusicLinks.Kind.PLAYLIST, "s|p1"), MusicLinks.parse(Uri.parse("ownmusic://play/playlist/s%7Cp1")))
    }

    @Test
    fun `other links are not ours`() {
        for (link in listOf("https://own.audio/play/album/x", "ownmusic://play/song/s%7C1", "ownmusic://play/album/", "ownmusic://play", "ownmusic://settings")) {
            assertNull(link, MusicLinks.parse(Uri.parse(link)))
        }
    }
}
