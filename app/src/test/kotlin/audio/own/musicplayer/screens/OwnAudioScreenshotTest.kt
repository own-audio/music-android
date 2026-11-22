package audio.own.musicplayer.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.kit.ownaudio.IdentifyProposal
import audio.own.kit.ownaudio.IdentifyRun
import audio.own.kit.ownaudio.IdentifyScope
import audio.own.kit.ownaudio.LibraryFolder
import audio.own.kit.ownaudio.RecordingCandidate
import audio.own.music.library.identify.IdentifyRunReview
import audio.own.music.library.identify.IdentifyTab
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.identify.IdentifyRunContent
import audio.own.musicplayer.identify.IdentifyRunUi
import audio.own.musicplayer.identify.IdentifyRunsContent
import audio.own.musicplayer.identify.IdentifyRunsState
import audio.own.musicplayer.identify.RecordingPickerContent
import audio.own.musicplayer.identify.RecordingPickerState
import audio.own.musicplayer.identify.RunActions
import audio.own.musicplayer.library.LibraryContent
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.SongMenu
import audio.own.musicplayer.library.SongRow
import audio.own.musicplayer.player.ChapterEditorContent
import audio.own.musicplayer.player.ChapterEditorState
import audio.own.musicplayer.player.ConcertSongsContent
import audio.own.musicplayer.player.PlayerContent
import audio.own.musicplayer.player.PlayerUi
import audio.own.musicplayer.player.Progress
import audio.own.player.music.MusicPlayer
import audio.own.player.music.PlayQueue
import audio.own.player.music.Track
import audio.own.player.music.TrackChapter
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M10 and M11: the song menu with sharing and identify, a concert's songs and their editor, the
 * player on a concert, Identify Library, a run to review and the recording picker, in light,
 * dark, Czech and at twice the font size (the player's sheets are always dark). Covers from the
 * Cover Art Archive load over the network, so their placeholders show.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OwnAudioScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val mine = Song("s|t1", "Kitchen Demo", artist = "Alex", album = "Home Recordings", albumId = "s|a1", track = 1, durationMs = 201_000, sharing = Sharing.MINE)
    private val ui = LibraryUi(canRate = true, canShare = true, canChoosePictures = true, canIdentify = true, canDownload = true)

    private fun shoot(name: String, dark: Boolean, screen: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalLibraryUi provides ui) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        // Menus and dialogs are windows of their own: the whole screen then.
        if (screen) captureScreenRoboImage("src/test/screenshots/$name.png") else compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Menu() = Box(Modifier.fillMaxSize()) {
        SongRow(mine, onClick = {})
        SongMenu(mine, expanded = true, onDismiss = {})
    }

    private val chapters = listOf(
        TrackChapter("Intro", 0.0),
        TrackChapter("Wide Open", 94.0),
        TrackChapter("The Long Road Home", 566.0),
        TrackChapter("Encore", 3_723.0),
    )
    private val concertTrack = Track("s|c1", "Live at the Old Hall", "The Neighbours", "Live at the Old Hall", 4_200)
    private val playing = MusicPlayer.State(
        currentTrack = concertTrack,
        isPlaying = true,
        position = PlayQueue.Position(1, 1),
        chapters = chapters,
        chapterIndex = 1,
        hasNext = true,
        hasPrevious = true,
    )

    @Composable
    private fun Songs() = Column { ConcertSongsContent(chapters, currentIndex = 1, canEdit = true, onJump = {}, onEdit = {}, onDone = {}) }

    @Composable
    private fun Editor() = Column {
        ChapterEditorContent(
            ChapterEditorState(
                listOf(
                    ChapterEditorState.Row(0, "Intro", "0:00"),
                    ChapterEditorState.Row(1, "Wide Open", "1:34"),
                    ChapterEditorState.Row(2, "The Long Road Home", "9:2x"),
                    ChapterEditorState.Row(3, "", "1:02:03"),
                ),
            ),
            onTitle = { _, _ -> }, onTime = { _, _ -> }, onStartHere = {}, onDelete = {}, onAdd = {}, onCancel = {}, onSave = {},
        )
    }

    @Composable
    private fun ConcertPlayer() = PlayerContent(
        ui = PlayerUi(playing, canIdentify = true),
        progress = { Progress(positionMs = 120_000, durationMs = 4_200_000, bufferedMs = 300_000) },
        onClose = {}, onStop = {}, onToggle = {}, onNext = {}, onPrevious = {}, onSeek = {}, onFavorite = {}, onShuffle = {}, onRepeat = {}, onSheet = {},
    )

    private fun run(status: String, done: Int = 3) = IdentifyRun(
        id = "r-$status",
        status = status,
        scope = IdentifyScope.library("Whole library"),
        tracks = 120,
        groupsTotal = 12,
        groupsDone = done,
        counts = IdentifyRun.Counts(sure = 41, review = 9, none = 3, accepted = 12),
        createdAt = Instant.parse("2026-11-20T10:00:00Z"),
    )

    @Composable
    private fun Runs() = IdentifyRunsContent(
        state = IdentifyRunsState(
            runs = Load.Loaded(
                listOf(
                    run("running"),
                    run("ready", 12).copy(id = "r2", scope = IdentifyScope("folder", "f1", label = "/music/vinyl rips")),
                    run("failed", 2).copy(id = "r3", error = "MusicBrainz could not be reached."),
                ),
            ),
            folders = listOf(LibraryFolder("f1", "/music", "music"), LibraryFolder("f2", "/music/vinyl rips", "music")),
        ),
        onBack = {}, onRetry = {}, onChoose = {}, onStart = {}, onConfirm = {}, onCancelConfirm = {}, onRun = {}, onDismissError = {},
    )

    private fun proposal(id: String, track: Int, title: String, match: String?, reasons: List<String> = emptyList(), decision: String = "pending", album: String = "Home Recordings") = IdentifyProposal(
        id = id,
        group = IdentifyProposal.Group("g1", "album", "Home Recordings"),
        track = IdentifyProposal.Song("t$id", title, artist = "Alex", album = album, discNumber = 1, trackNumber = track, durationSecs = 201),
        proposalClass = "review",
        reasons = reasons,
        proposal = match?.let { IdentifyProposal.Match("m$id", it, artist = "Alex Band", album = "Kitchen Sessions", mbReleaseId = "rel1", year = 2019, score = 80) },
        decision = decision,
    )

    @Composable
    private fun Review() = IdentifyRunContent(
        ui = IdentifyRunUi(
            IdentifyRunReview.State(
                run = run("running", 7),
                tab = IdentifyTab.REVIEW,
                proposals = listOf(
                    proposal("1", 1, "Kitchen Demo", "Kitchen Demo (live)", listOf("weak")),
                    proposal("2", 2, "Track 02", "Morning Song", listOf("album_differs", "artist_differs")),
                    proposal("3", 3, "Untitled", null),
                    proposal("4", 4, "Last One", "Last One", decision = "accepted"),
                ),
                isLoading = false,
            ),
        ),
        onBack = {},
        actions = RunActions(),
    )

    @Composable
    private fun Picker() = RecordingPickerContent(
        state = RecordingPickerState(
            title = "Track 02",
            artist = "Alex",
            album = "Home Recordings",
            results = listOf(
                RecordingCandidate("m1", "Morning Song", artist = "Alex Band", album = "Kitchen Sessions", mbReleaseId = "rel1", durationMs = 203_000, year = 2019),
                RecordingCandidate("m2", "Morning Song (demo)", artist = "Alex Band", album = "Early Tapes", mbReleaseId = "rel2", durationMs = 251_000, year = 2015),
            ),
            searched = true,
            widenedFrom = "Home Recordings",
        ),
        songTitle = "Track 02",
        songMs = 201_000,
        onCancel = {}, onTitle = {}, onArtist = {}, onAlbum = {}, onSearch = {}, onPick = {}, onLink = {}, onUseLink = {}, onDismissError = {},
    )

    @Test fun songMenuLight() = shoot("song_menu_own_light", false, screen = true) { Menu() }
    @Test fun songMenuDark() = shoot("song_menu_own_dark", true, screen = true) { Menu() }
    @Test @Config(qualifiers = "+cs") fun songMenuCzech() = shoot("song_menu_own_cs", false, screen = true) { Menu() }
    @Test @Config(fontScale = 2f) fun songMenuLargestText() = shoot("song_menu_own_font200", false, screen = true) { Menu() }

    @Test fun libraryIdentifyLight() = shoot("library_identify_light", false) { LibraryContent(concerts = true, identify = true) {} }
    @Test @Config(qualifiers = "+cs") fun libraryIdentifyCzech() = shoot("library_identify_cs", false) { LibraryContent(concerts = true, identify = true) {} }

    // The player and its sheets are always dark.
    @Test fun concertSongsDark() = shoot("concert_songs_dark", true) { Songs() }
    @Test @Config(qualifiers = "+cs") fun concertSongsCzech() = shoot("concert_songs_cs", true) { Songs() }
    @Test @Config(fontScale = 2f) fun concertSongsLargestText() = shoot("concert_songs_font200", true) { Songs() }

    @Test fun chapterEditorDark() = shoot("chapter_editor_dark", true) { Editor() }
    @Test @Config(qualifiers = "+cs") fun chapterEditorCzech() = shoot("chapter_editor_cs", true) { Editor() }
    @Test @Config(fontScale = 2f) fun chapterEditorLargestText() = shoot("chapter_editor_font200", true) { Editor() }

    @Test fun concertPlayerDark() = shoot("player_concert_dark", true) { ConcertPlayer() }
    @Test @Config(qualifiers = "+cs") fun concertPlayerCzech() = shoot("player_concert_cs", true) { ConcertPlayer() }
    @Test @Config(fontScale = 2f) fun concertPlayerLargestText() = shoot("player_concert_font200", true) { ConcertPlayer() }

    @Test fun identifyRunsLight() = shoot("identify_runs_light", false) { Runs() }
    @Test fun identifyRunsDark() = shoot("identify_runs_dark", true) { Runs() }
    @Test @Config(qualifiers = "+cs") fun identifyRunsCzech() = shoot("identify_runs_cs", false) { Runs() }
    @Test @Config(fontScale = 2f) fun identifyRunsLargestText() = shoot("identify_runs_font200", false) { Runs() }

    @Test fun identifyRunLight() = shoot("identify_run_light", false) { Review() }
    @Test fun identifyRunDark() = shoot("identify_run_dark", true) { Review() }
    @Test @Config(qualifiers = "+cs") fun identifyRunCzech() = shoot("identify_run_cs", false) { Review() }
    @Test @Config(fontScale = 2f) fun identifyRunLargestText() = shoot("identify_run_font200", false) { Review() }

    @Test fun recordingPickerLight() = shoot("recording_picker_light", false) { Picker() }
    @Test fun recordingPickerDark() = shoot("recording_picker_dark", true) { Picker() }
    @Test @Config(qualifiers = "+cs") fun recordingPickerCzech() = shoot("recording_picker_cs", false) { Picker() }
    @Test @Config(fontScale = 2f) fun recordingPickerLargestText() = shoot("recording_picker_font200", false) { Picker() }
}
