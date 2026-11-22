package audio.own.musicplayer.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.music.library.AlbumSort
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.library.AlbumContent
import audio.own.musicplayer.library.AlbumsScreen
import audio.own.musicplayer.library.AlbumsViewModel
import audio.own.musicplayer.library.ArtistContent
import audio.own.musicplayer.library.ArtistsScreen
import audio.own.musicplayer.library.ArtistsViewModel
import audio.own.musicplayer.library.FakeMusicSource
import audio.own.musicplayer.library.LibraryRoutes
import audio.own.musicplayer.library.LibrarySection
import audio.own.musicplayer.library.LibrarySidebar
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.SongsScreen
import audio.own.musicplayer.library.SongsViewModel
import audio.own.musicplayer.player.MiniPlayer
import audio.own.musicplayer.player.PlayerContent
import audio.own.musicplayer.player.PlayerUi
import audio.own.musicplayer.player.Progress
import audio.own.musicplayer.ui.Tab
import audio.own.musicplayer.ui.TabsScreen
import audio.own.musicplayer.ui.WithSidebar
import audio.own.musicplayer.ui.isWide
import audio.own.player.core.SleepTimer
import audio.own.player.music.FormatBadge
import audio.own.player.music.MusicPlayer
import audio.own.player.music.PlayQueue
import audio.own.player.music.RepeatMode
import audio.own.player.music.Track
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * The Library's lists (artists, the album grid, songs, an artist) in light, dark, Czech and at
 * twice the font size, and the app on a tablet in landscape and on an unfolded foldable: the
 * tabs as a rail, the Library's sidebar, the grid's density, lists a readable width, the player
 * in a centred column. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LayoutScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val names = listOf(
        "Jazz Sampler" to "Kevin MacLeod", "The Run" to "Kai Engel", "Classical Sampler" to "Kevin MacLeod",
        "Satin" to "Kai Engel", "Cinematic" to "Kevin MacLeod", "Night Owl" to "Broke For Free",
        "Directionless EP" to "Broke For Free", "Something Elated" to "Broke For Free", "Easy Listening" to "Kevin MacLeod",
        "Chapter One" to "Lee Rosevere", "Music for Podcasts" to "Lee Rosevere", "Ambient Works" to "Chad Crouch",
    )
    private val albums = names.mapIndexed { index, (name, artist) -> Album("s|a$index", name, artist = artist, artistId = "s|$artist", songCount = 8, year = 2014 + index % 6) }
    private val songs = listOf(
        Song("s|t1", "AcidJazz", artist = "Kevin MacLeod", album = "Jazz Sampler", albumId = "s|a0", track = 1, durationMs = 208_000),
        Song("s|t2", "Acid Trumpet", artist = "Kevin MacLeod", album = "Jazz Sampler", albumId = "s|a0", track = 2, durationMs = 208_000, isFavorite = true),
        Song("s|t3", "As I Figure", artist = "Kevin MacLeod", album = "Jazz Sampler", albumId = "s|a0", track = 3, durationMs = 216_000, sharing = Sharing.SHARED),
        Song("s|t4", "Backed Vibes Clean", artist = "Kevin MacLeod", album = "Jazz Sampler", albumId = "s|a0", track = 4, durationMs = 228_000),
        Song("s|t5", "Night Owl", artist = "Broke For Free", album = "Directionless EP", albumId = "s|a6", track = 1, durationMs = 375_000),
        Song("s|t6", "Something Elated", artist = "Broke For Free", album = "Something Elated", albumId = "s|a7", track = 1, durationMs = 201_000, isFavorite = true),
        Song("s|t7", "Summer Spliff", artist = "Broke For Free", album = "Directionless EP", albumId = "s|a6", track = 2, durationMs = 223_000),
    )
    private val artists = listOf("Broke For Free", "Chad Crouch", "Kai Engel", "Kevin MacLeod", "Lee Rosevere").map { Artist("s|$it", it, albumCount = albums.count { a -> a.artist == it }) }

    /** The pretend server behind the real view models. */
    private val source: MusicSource = object : MusicSource by FakeMusicSource() {
        override suspend fun artists() = this@LayoutScreenshotTest.artists
        override suspend fun albums(sort: AlbumSort, limit: Int, offset: Int) = albums.drop(offset).take(limit)
        override suspend fun songs(limit: Int, offset: Int) = songs.drop(offset).take(limit)
        override suspend fun album(id: String) = AlbumWithSongs(albums.first { it.id == id }, songs.take(4))
    }

    private val tracks = songs.take(4).map { Track(it.id, it.title, it.artist, "Jazz Sampler", (it.durationMs!! / 1000).toInt()) }
    private val playing = MusicPlayer.State(
        currentTrack = tracks[0], isPlaying = true, position = PlayQueue.Position(1, 4), played = emptyList(), upcoming = tracks.drop(1),
        isShuffled = true, repeatMode = RepeatMode.ALL, hasNext = true, hasPrevious = true, format = FormatBadge("FLAC", true, 44_100, 16),
    )
    private val progress = { Progress(positionMs = 83_000, durationMs = 296_000, bufferedMs = 150_000) }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalLibraryUi provides LibraryUi(currentTrackId = "s|t1", isPlaying = true, canRate = true, isDownloaded = { it == "s|t2" })) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable private fun Artists() = ArtistsScreen(remember { ArtistsViewModel(source) }, onBack = {}, onArtist = {})

    @Composable private fun Albums() = AlbumsScreen(remember { AlbumsViewModel(source, AlbumSort.NAME) }, onBack = {}, onAlbum = {})

    @Composable private fun Songs() = SongsScreen(remember { SongsViewModel(source) }, onBack = {})

    @Composable
    private fun ArtistPage() = ArtistContent(
        Load.Loaded(albums.filter { it.artist == "Kevin MacLeod" }), starting = false, known = artists[3],
        onBack = {}, onAlbum = {}, onRetry = {}, onPlayAll = {},
    )

    @Composable
    private fun Player() = PlayerContent(
        ui = PlayerUi(playing, SleepTimer.State(), isFavorite = true, crossfadeOn = true),
        progress = progress,
        onClose = {}, onStop = {}, onToggle = {}, onNext = {}, onPrevious = {}, onSeek = {}, onFavorite = {}, onShuffle = {}, onRepeat = {}, onSheet = {},
    )

    /** The Library tab as the app lays it out: the sidebar beside the page when the window is expanded. */
    @Composable
    private fun LibraryTab(selected: String, page: @Composable () -> Unit) = TabsScreen(
        selected = Tab.LIBRARY,
        onSelect = {},
        above = { MiniPlayer(playing, progress, onToggle = {}, onNext = {}, onOpen = {}) },
    ) {
        if (isWide()) WithSidebar(sidebar = { LibrarySidebar(concerts = true, selected = selected, onOpen = {}) }) { page() } else page()
    }

    @Test fun artistsLight() = shoot("artists_light", false) { Artists() }
    @Test fun artistsDark() = shoot("artists_dark", true) { Artists() }
    @Test @Config(qualifiers = "+cs") fun artistsCzech() = shoot("artists_cs", false) { Artists() }
    @Test @Config(fontScale = 2f) fun artistsLargestText() = shoot("artists_font200", false) { Artists() }

    @Test fun albumsLight() = shoot("albums_light", false) { Albums() }
    @Test fun albumsDark() = shoot("albums_dark", true) { Albums() }
    @Test @Config(qualifiers = "+cs") fun albumsCzech() = shoot("albums_cs", false) { Albums() }
    @Test @Config(fontScale = 2f) fun albumsLargestText() = shoot("albums_font200", false) { Albums() }

    @Test fun songsLight() = shoot("songs_light", false) { Songs() }
    @Test fun songsDark() = shoot("songs_dark", true) { Songs() }
    @Test @Config(qualifiers = "+cs") fun songsCzech() = shoot("songs_cs", false) { Songs() }
    @Test @Config(fontScale = 2f) fun songsLargestText() = shoot("songs_font200", false) { Songs() }

    @Test fun artistLight() = shoot("artist_light", false) { ArtistPage() }
    @Test fun artistDark() = shoot("artist_dark", true) { ArtistPage() }
    @Test @Config(qualifiers = "+cs") fun artistCzech() = shoot("artist_cs", false) { ArtistPage() }
    @Test @Config(fontScale = 2f) fun artistLargestText() = shoot("artist_font200", false) { ArtistPage() }

    // A tablet in landscape: the tabs as a rail, the Library's sidebar, the grid, lists a readable width.
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletAlbums() = shoot("tablet_albums", false) { LibraryTab(LibrarySection.ALBUMS.route) { Albums() } }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletSongs() = shoot("tablet_songs", true) { LibraryTab(LibraryRoutes.SONGS) { Songs() } }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletAlbum() = shoot("tablet_album", false) {
        LibraryTab(LibrarySection.ALBUMS.route) { AlbumContent(Load.Loaded(AlbumWithSongs(albums[0], songs.take(4))), albums[0], onBack = {}, onArtist = { _: Artist -> }, onRetry = {}) }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletPlayer() = shoot("tablet_player", true) { Player() }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun tabletPortraitPlayer() = shoot("tablet_portrait_player", true) { Player() }

    // An unfolded foldable: medium width, so a rail and no sidebar; the grid gets more columns.
    @Test @Config(qualifiers = "w673dp-h841dp-mdpi")
    fun foldableAlbums() = shoot("foldable_albums", false) { LibraryTab(LibrarySection.ALBUMS.route) { Albums() } }

    @Test @Config(qualifiers = "w673dp-h841dp-mdpi", fontScale = 2f)
    fun foldableSongsLargestText() = shoot("foldable_songs_font200", false) { LibraryTab(LibraryRoutes.SONGS) { Songs() } }
}
