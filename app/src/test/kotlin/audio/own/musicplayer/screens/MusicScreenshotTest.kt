package audio.own.musicplayer.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.home.HomeContent
import audio.own.musicplayer.home.HomeUi
import audio.own.musicplayer.library.AlbumContent
import audio.own.musicplayer.library.HomeContentState
import audio.own.musicplayer.library.LibraryContent
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.SearchState
import audio.own.musicplayer.player.CrossfadeContent
import audio.own.musicplayer.player.EqualizerContent
import audio.own.musicplayer.player.LyricsContent
import audio.own.musicplayer.player.MiniPlayer
import audio.own.musicplayer.player.PlayerContent
import audio.own.musicplayer.player.PlayerUi
import audio.own.musicplayer.player.Progress
import audio.own.musicplayer.player.QueueContent
import audio.own.musicplayer.player.SheetBody
import audio.own.musicplayer.player.SleepContent
import audio.own.musicplayer.search.SearchContent
import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.player.core.SleepTimer
import audio.own.player.music.EqPreset
import audio.own.player.music.EqualizerSettings
import audio.own.player.music.FormatBadge
import audio.own.player.music.MusicPlayer
import audio.own.player.music.PlayQueue
import audio.own.player.music.RepeatMode
import audio.own.player.music.Track
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The M4 screens in light, dark, Czech and at twice the font size. Covers are left out (they
 * load over the network), so the placeholders show. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MusicScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        Song("s|t1", "AcidJazz", artist = "Kevin MacLeod", track = 1, discNumber = 1, durationMs = 208_000),
        Song("s|t2", "Acid Trumpet", artist = "Kevin MacLeod", track = 2, discNumber = 1, durationMs = 208_000, isFavorite = true),
        Song("s|t3", "As I Figure", artist = "Kevin MacLeod", track = 3, discNumber = 1, durationMs = 216_000, sharing = Sharing.SHARED),
        Song("s|t4", "Backed Vibes Clean", artist = "Kevin MacLeod", track = 1, discNumber = 2, durationMs = 228_000),
    )
    private val album = Album("s|a1", "Jazz Sampler", artist = "Kevin MacLeod", artistId = "s|r1", songCount = 4, year = 2014)
    private val albums = listOf(album, Album("s|a2", "The Run", artist = "Kai Engel"), Album("s|a3", "Classical Sampler", artist = "Kevin MacLeod"))
    private val tracks = songs.map { Track(it.id, it.title, it.artist, "Jazz Sampler", (it.durationMs!! / 1000).toInt()) }
    private val playing = MusicPlayer.State(
        currentTrack = tracks[0],
        isPlaying = true,
        position = PlayQueue.Position(1, 4),
        played = emptyList(),
        upcoming = tracks.drop(1),
        isShuffled = true,
        repeatMode = RepeatMode.ALL,
        hasNext = true,
        hasPrevious = true,
        format = FormatBadge("FLAC", true, 44_100, 16),
        replayGainDb = -2.5,
    )
    private val progress = { Progress(positionMs = 62_000, durationMs = 208_000, bufferedMs = 120_000) }
    private val servers = listOf(
        ServerRecord("s", "own_audio", "own.audio demo", "https://demo.example.com", ServerAuth.OwnAudio("a@example.com", null)),
        ServerRecord("n", "subsonic", "Navidrome", "https://music.example.com", ServerAuth.OwnAudio("a@example.com", null)),
    )

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalLibraryUi provides LibraryUi(currentTrackId = "s|t1", isPlaying = true, canRate = true)) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Home() = HomeContent(
        ui = HomeUi(
            content = Load.Loaded(HomeContentState(recent = albums, newest = albums.reversed(), playlists = listOf(Playlist("s|p1", "Evening", songCount = 12)))),
            servers = servers,
            activeId = "s",
        ),
        onSelectServer = {}, onOfflineMode = {}, onSettings = {}, onShuffleAll = {}, onRefresh = {}, onRetry = {}, onAlbum = {}, onPlaylist = {}, onShelf = {},
    )

    @Composable
    private fun Library() = LibraryContent(concerts = true) {}

    @Composable
    private fun AlbumPage() = AlbumContent(Load.Loaded(AlbumWithSongs(album, songs)), album, onBack = {}, onArtist = { _: Artist -> }, onRetry = {})

    @Composable
    private fun Search() = SearchContent(
        state = SearchState(query = "jazz", result = SearchResult(listOf(Artist("s|r1", "Kevin MacLeod", albumCount = 3)), albums.take(1), songs.take(2))),
        serverName = "own.audio demo",
        isOffline = false,
        onQuery = {}, onArtist = {}, onAlbum = {},
        focusOnOpen = false,
    )

    @Composable
    private fun Mini() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth()) {
            MiniPlayer(playing, progress, onToggle = {}, onNext = {}, onOpen = {})
            MiniPlayer(playing.copy(isPlaying = false, hasNext = false), { Progress(isRestored = true) }, onToggle = {}, onNext = {}, onOpen = {})
        }
    }

    @Composable
    private fun Player() = PlayerContent(
        ui = PlayerUi(playing, SleepTimer.State(remainingSecs = 754.0), isFavorite = true, crossfadeOn = true),
        progress = progress,
        onClose = {}, onStop = {}, onToggle = {}, onNext = {}, onPrevious = {}, onSeek = {}, onFavorite = {}, onShuffle = {}, onRepeat = {}, onSheet = {},
    )

    @Composable
    private fun Sheet(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) { SheetBody(title, onDone = {}) { content() } }

    @Composable
    private fun Queue() = Sheet("Queue") { QueueContent(playing.copy(played = tracks.take(1)), onJump = {}, onMove = { _, _ -> }, onRemove = {}) }

    @Composable
    private fun Sleep() = Sheet("Sleep Timer") { SleepContent(SleepTimer.State(), {}, {}, {}, {}) }

    @Composable
    private fun SleepRunning() = Sheet("Sleep Timer") { SleepContent(SleepTimer.State(remainingSecs = 754.0), {}, {}, {}, {}) }

    @Composable
    private fun Crossfade() = Sheet("Crossfade") { CrossfadeContent(PlaybackSettings(crossfadeEnabled = true, fadeSecs = 6), {}, {}, {}) }

    @Composable
    private fun LyricsSheet() = Surface(color = androidx.compose.ui.graphics.Color.Black) {
        Sheet("AcidJazz") {
            LyricsContent(
                Load.Loaded(listOf(Lyrics(listOf(Lyrics.Line(0, "First line"), Lyrics.Line(60_000, "The line playing now"), Lyrics.Line(90_000, "And the next one")), isSynced = true))),
                progress, onSeek = {}, onRetry = {}, choice = 0,
            )
        }
    }

    @Composable
    private fun NoLyrics() = Surface(color = androidx.compose.ui.graphics.Color.Black) {
        Sheet("AcidJazz") { LyricsContent(Load.Loaded(emptyList()), progress, onSeek = {}, onRetry = {}, choice = 0) }
    }

    @Composable
    private fun Equalizer() = Sheet("Equalizer") {
        EqualizerContent(EqualizerSettings(enabled = true, bandGainsDb = EqPreset.all[1].bandGainsDb, presetName = "Bass Boost"), {}, {}, { _, _ -> }, {}, {})
    }

    @Test fun homeLight() = shoot("home_light", false) { Home() }
    @Test fun homeDark() = shoot("home_dark", true) { Home() }
    @Test @Config(qualifiers = "+cs") fun homeCzech() = shoot("home_cs", false) { Home() }
    @Test @Config(fontScale = 2f) fun homeLargestText() = shoot("home_font200", false) { Home() }

    @Test fun libraryLight() = shoot("library_light", false) { Library() }
    @Test fun libraryDark() = shoot("library_dark", true) { Library() }
    @Test @Config(qualifiers = "+cs") fun libraryCzech() = shoot("library_cs", false) { Library() }
    @Test @Config(fontScale = 2f) fun libraryLargestText() = shoot("library_font200", false) { Library() }

    @Test fun albumLight() = shoot("album_light", false) { AlbumPage() }
    @Test fun albumDark() = shoot("album_dark", true) { AlbumPage() }
    @Test @Config(qualifiers = "+cs") fun albumCzech() = shoot("album_cs", false) { AlbumPage() }
    @Test @Config(fontScale = 2f) fun albumLargestText() = shoot("album_font200", false) { AlbumPage() }

    @Test fun searchLight() = shoot("search_light", false) { Search() }
    @Test fun searchDark() = shoot("search_dark", true) { Search() }
    @Test @Config(qualifiers = "+cs") fun searchCzech() = shoot("search_cs", false) { Search() }
    @Test @Config(fontScale = 2f) fun searchLargestText() = shoot("search_font200", false) { Search() }

    @Test fun miniPlayerLight() = shoot("miniplayer_light", false) { Mini() }
    @Test fun miniPlayerDark() = shoot("miniplayer_dark", true) { Mini() }
    @Test @Config(qualifiers = "+cs") fun miniPlayerCzech() = shoot("miniplayer_cs", false) { Mini() }
    @Test @Config(fontScale = 2f) fun miniPlayerLargestText() = shoot("miniplayer_font200", false) { Mini() }

    // The player and its sheets are always dark: light and dark are the same, so dark, Czech and 200 %.
    @Test fun playerDark() = shoot("player_dark", true) { Player() }
    @Test @Config(qualifiers = "+cs") fun playerCzech() = shoot("player_cs", true) { Player() }
    @Test @Config(fontScale = 2f) fun playerLargestText() = shoot("player_font200", true) { Player() }

    @Test fun queueDark() = shoot("queue_dark", true) { Queue() }
    @Test @Config(qualifiers = "+cs") fun queueCzech() = shoot("queue_cs", true) { Queue() }
    @Test @Config(fontScale = 2f) fun queueLargestText() = shoot("queue_font200", true) { Queue() }

    @Test fun sleepDark() = shoot("sleep_dark", true) { Sleep() }
    @Test fun sleepRunningDark() = shoot("sleep_running_dark", true) { SleepRunning() }
    @Test @Config(qualifiers = "+cs") fun sleepCzech() = shoot("sleep_cs", true) { Sleep() }
    @Test @Config(fontScale = 2f) fun sleepLargestText() = shoot("sleep_font200", true) { Sleep() }

    @Test fun crossfadeDark() = shoot("crossfade_dark", true) { Crossfade() }
    @Test @Config(qualifiers = "+cs") fun crossfadeCzech() = shoot("crossfade_cs", true) { Crossfade() }
    @Test @Config(fontScale = 2f) fun crossfadeLargestText() = shoot("crossfade_font200", true) { Crossfade() }

    @Test fun lyricsDark() = shoot("lyrics_dark", true) { LyricsSheet() }
    @Test fun noLyricsDark() = shoot("lyrics_none_dark", true) { NoLyrics() }
    @Test @Config(qualifiers = "+cs") fun lyricsCzech() = shoot("lyrics_cs", true) { NoLyrics() }
    @Test @Config(fontScale = 2f) fun lyricsLargestText() = shoot("lyrics_font200", true) { LyricsSheet() }

    @Test fun equalizerDark() = shoot("equalizer_dark", true) { Equalizer() }
    @Test @Config(qualifiers = "+cs") fun equalizerCzech() = shoot("equalizer_cs", true) { Equalizer() }
    @Test @Config(fontScale = 2f) fun equalizerLargestText() = shoot("equalizer_font200", true) { Equalizer() }
}
