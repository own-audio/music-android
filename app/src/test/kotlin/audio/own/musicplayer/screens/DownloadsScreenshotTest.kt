package audio.own.musicplayer.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.components.OfflineBanner
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.music.library.AlbumWithSongs
import audio.own.music.library.model.Album
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.downloads.DownloadedCollectionContent
import audio.own.musicplayer.downloads.DownloadedCollectionRow
import audio.own.musicplayer.downloads.DownloadedSongRow
import audio.own.musicplayer.downloads.DownloadsContent
import audio.own.musicplayer.downloads.DownloadsContentState
import audio.own.musicplayer.library.AlbumContent
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.player.MiniPlayer
import audio.own.player.music.MusicPlayer
import audio.own.player.music.Track
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import audio.own.core.strings.R as SR

/** The Downloads tab, a downloaded album, an album partly downloaded offline, and the offline banner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DownloadsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        Song("s|t1", "AcidJazz", artist = "Kevin MacLeod", track = 1, durationMs = 208_000),
        Song("s|t2", "Acid Trumpet", artist = "Kevin MacLeod", track = 2, durationMs = 208_000),
        Song("s|t3", "As I Figure", artist = "Kevin MacLeod", track = 3, durationMs = 216_000),
    )

    private val filled = DownloadsContentState(
        totalBytes = 1_234_000_000,
        pending = 3,
        failed = 2,
        albums = listOf(
            DownloadedCollectionRow("album:s|a1", "Jazz Sampler", null, "Home", 12, 12),
            DownloadedCollectionRow("album:s|a2", "Cinematic", null, "own.audio", 4, 10),
        ),
        playlists = listOf(DownloadedCollectionRow("playlist:s|p1", "Road Trip", null, null, 20, 20)),
        songs = listOf(DownloadedSongRow(songs[2], "Home", 8_400_000)),
    )

    private fun shoot(name: String, dark: Boolean, ui: LibraryUi = LibraryUi(), content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalLibraryUi provides ui) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Downloads(state: DownloadsContentState = filled) = DownloadsContent(state, null, {}, {}, { _, _ -> }, {}, {}, {})

    @Test fun downloadsLight() = shoot("downloads_light", false) { Downloads() }
    @Test fun downloadsDark() = shoot("downloads_dark", true) { Downloads() }
    @Test @Config(qualifiers = "+cs") fun downloadsCzech() = shoot("downloads_cs", false) { Downloads() }
    @Test @Config(fontScale = 2f) fun downloadsLargestText() = shoot("downloads_font200", false) { Downloads() }
    @Test fun downloadsEmpty() = shoot("downloads_empty_light", false) { Downloads(DownloadsContentState()) }
    @Test @Config(qualifiers = "+cs") fun downloadsEmptyCzech() = shoot("downloads_empty_cs", false) { Downloads(DownloadsContentState()) }

    @Test fun downloadedAlbumDark() = shoot("downloaded_album_dark", true) {
        DownloadedCollectionContent("Jazz Sampler", "Kevin MacLeod", null, songs, {}, { _, _ -> }, {})
    }

    /** Offline: the album lists all its songs, the ones not downloaded greyed out; the arrow shows 2 of 3. */
    @Test fun albumOfflinePartlyDownloaded() = shoot(
        "album_offline_partial_light",
        false,
        LibraryUi(isOffline = true, canDownload = true, isDownloaded = { it != "s|t2" }, collectionProgress = { 2 to 3 }),
    ) {
        val album = Album("s|a1", "Jazz Sampler", artist = "Kevin MacLeod", songCount = 3, year = 2014)
        AlbumContent(Load.Loaded(AlbumWithSongs(album, songs)), album, {}, {}, {})
    }

    @Composable
    private fun Banners() = Column {
        OfflineBanner(stringResource(R.string.offline_no_network))
        OfflineBanner(stringResource(R.string.offline_by_choice), actionLabel = stringResource(SR.string.turn_off)) {}
        OfflineBanner(stringResource(R.string.offline_mobile_data), actionLabel = stringResource(R.string.stream)) {}
        MiniPlayer(
            MusicPlayer.State(currentTrack = Track("s|t1", "AcidJazz", artist = "Kevin MacLeod", durationSecs = 208), isPlaying = true),
            { audio.own.musicplayer.player.Progress(60_000, 208_000) },
            onToggle = {}, onNext = {}, onOpen = {},
        )
    }

    @Test fun offlineBannersLight() = shoot("offline_banners_light", false) { Banners() }
    @Test fun offlineBannersDark() = shoot("offline_banners_dark", true) { Banners() }
    @Test @Config(qualifiers = "+cs") fun offlineBannersCzech() = shoot("offline_banners_cs", false) { Banners() }
}
