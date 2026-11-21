package audio.own.musicplayer.downloads

import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.musicplayer.settings.PlaybackSettingsStore
import audio.own.musicplayer.ui.playableOffline
import audio.own.music.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each downloads setting changes what it says, and survives a restart. */
class DownloadSettingsTest {
    @Test
    fun `the defaults - Wi-Fi only, no limit, downloads only when offline`() {
        val s = PlaybackSettingsStore(PlaybackSettingsStore.Memory()).current
        assertTrue(s.downloadsWifiOnly)
        assertNull(s.keepAtMostGb)
        assertNull(s.keepAtMostBytes)
        assertFalse(s.offlineWholeLibrary)
        assertFalse(s.offlineMode)
    }

    @Test
    fun `each one is kept`() {
        val memory = PlaybackSettingsStore.Memory()
        PlaybackSettingsStore(memory).update { it.copy(downloadsWifiOnly = false, keepAtMostGb = 5, offlineWholeLibrary = true, offlineMode = true) }
        val read = PlaybackSettingsStore(memory).current
        assertFalse(read.downloadsWifiOnly)
        assertEquals(5, read.keepAtMostGb)
        assertEquals(5_000_000_000L, read.keepAtMostBytes)
        assertTrue(read.offlineWholeLibrary)
        assertTrue(read.offlineMode)
        PlaybackSettingsStore(memory).update { it.copy(keepAtMostGb = null) }
        assertNull(PlaybackSettingsStore(memory).current.keepAtMostGb)
    }

    @Test
    fun `Keep at Most offers the iOS choices`() {
        assertEquals(listOf(null, 1, 2, 5, 10, 20, 50), PlaybackSettings.KEEP_AT_MOST_CHOICES)
    }

    @Test
    fun `offline, a tapped song that isn't downloaded starts at the next that is`() {
        val songs = (1..4).map { Song("s|t$it", "T$it") }
        val downloaded = setOf("s|t1", "s|t3")
        val (playable, start) = playableOffline(songs, "s|t2") { it in downloaded }
        assertEquals(listOf("s|t1", "s|t3"), playable.map { it.id })
        assertEquals("s|t3", start)
        assertEquals("s|t1", playableOffline(songs, "s|t1") { it in downloaded }.second)
        assertTrue(playableOffline(songs, null) { false }.first.isEmpty())
    }
}
