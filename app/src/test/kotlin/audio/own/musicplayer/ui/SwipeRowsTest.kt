package audio.own.musicplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.downloads.DownloadedCollectionRow
import audio.own.musicplayer.downloads.DownloadsContent
import audio.own.musicplayer.downloads.DownloadsContentState
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.PlaylistActions
import audio.own.musicplayer.library.PlaylistContent
import audio.own.musicplayer.library.PlaylistPageState
import audio.own.musicplayer.player.ChapterEditorContent
import audio.own.musicplayer.player.ChapterEditorState
import audio.own.musicplayer.player.QueueContent
import audio.own.musicplayer.servers.ServerKinds
import audio.own.musicplayer.settings.AppLanguage
import audio.own.musicplayer.settings.SettingsContent
import audio.own.musicplayer.settings.SettingsUi
import audio.own.player.music.MusicPlayer
import audio.own.player.music.PlayQueue
import audio.own.player.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every swipe-to-remove row: one swipe runs its action once, and a row that stays in its list
 * (the action asks first, or the removal did not happen) springs back instead of staying open.
 * The Podcasts app's rows ran their action again and again while they stayed open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class SwipeRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
            CompositionLocalProvider(LocalLibraryUi provides LibraryUi()) { content() }
        }
    }

    private fun swipe(text: String) {
        compose.onNodeWithText(text).performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    private fun assertClosed(text: String) {
        val bounds = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("$text is back in place, not left open at ${bounds.left}", bounds.left >= 0.dp && bounds.left < 200.dp)
    }

    private fun server(id: String, name: String) = ServerRecord(
        id = id, kind = ServerKinds.OWN_AUDIO, displayName = name, host = "https://$id.example.com",
        auth = ServerAuth.OwnAudio("alex@example.com", StoredTokens("a", "r", 0)),
    )

    @Test
    fun `each server swiped away asks once, also the one that moved up into a removed row's place`() {
        var servers by mutableStateOf(listOf(server("a", "First"), server("b", "Second"), server("c", "Third")))
        val removed = mutableListOf<String>()
        show {
            SettingsContent(
                ui = SettingsUi(servers, activeId = "a", accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1"),
                onBack = {}, onOpenServer = {}, onRemoveServer = { id -> removed += id; servers = servers.filterNot { it.id == id } },
                onAddServer = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
            )
        }
        for (name in listOf("First", "Second", "Third")) {
            swipe(name)
            compose.onNodeWithText("Remove $name?").assertExists()
            compose.onAllNodesWithText("Remove").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("a", "b", "c"), removed)
    }

    @Test
    fun `a server swiped and then kept springs back and asks again on the next swipe`() {
        var asked = 0
        show {
            SettingsContent(
                ui = SettingsUi(listOf(server("a", "First")), activeId = "a", accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1"),
                onBack = {}, onOpenServer = {}, onRemoveServer = {}, onAddServer = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
            )
        }
        repeat(2) {
            swipe("First")
            compose.onNodeWithText("Remove First?").assertExists()
            asked++
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertClosed("First")
        }
        assertEquals(2, asked)
    }

    private val songs = listOf(
        Song("s|t1", "AcidJazz", artist = "Kevin MacLeod", durationMs = 208_000, playlistEntryId = "e1"),
        Song("s|t2", "Acid Trumpet", artist = "Kevin MacLeod", durationMs = 208_000, playlistEntryId = "e2"),
    )

    @Test
    fun `one swipe on a playlist song removes it once, and a song that stays springs back`() {
        val removed = mutableListOf<Int>()
        show {
            PlaylistContent(
                state = PlaylistPageState(Playlist("s|p1", "Evening", owner = "alex", songCount = 2), Load.Loaded(songs)),
                actions = PlaylistActions(isOwn = true, canChooseCover = false, canShare = false, canReorder = true),
                onBack = {}, onRetry = {}, onRename = {}, onChooseCover = {}, onShare = {}, onDelete = {},
                onRemove = { removed += it }, onMove = { _, _ -> },
            )
        }
        swipe("Acid Trumpet")
        assertEquals("one swipe, one removal", listOf(1), removed)
        assertClosed("Acid Trumpet")
        swipe("Acid Trumpet")
        assertEquals(listOf(1, 1), removed)
    }

    @Test
    fun `one swipe on a download removes it once, and a row that stays springs back`() {
        val removed = mutableListOf<String>()
        val state = DownloadsContentState(
            totalBytes = 1_000_000, pending = 0, failed = 0,
            albums = listOf(DownloadedCollectionRow("album:s|a1", "Jazz Sampler", null, "Home", 12, 12)),
            playlists = emptyList(), songs = emptyList(),
        )
        show { DownloadsContent(state, null, {}, {}, { _, _ -> }, onRemoveCollection = { removed += it }, onRemoveSong = {}, onRemoveAll = {}) }
        swipe("Jazz Sampler")
        assertEquals(listOf("album:s|a1"), removed)
        assertClosed("Jazz Sampler")
        swipe("Jazz Sampler")
        assertEquals("the second swipe works too", listOf("album:s|a1", "album:s|a1"), removed)
    }

    @Test
    fun `one swipe on an upcoming song removes it once, and a song that stays springs back`() {
        val tracks = listOf(Track("t1", "AcidJazz", "Kevin MacLeod", "Jazz Sampler", 208), Track("t2", "Acid Trumpet", "Kevin MacLeod", "Jazz Sampler", 208), Track("t3", "As I Figure", "Kevin MacLeod", "Jazz Sampler", 216))
        val state = MusicPlayer.State(currentTrack = tracks[0], isPlaying = true, position = PlayQueue.Position(1, 3), played = emptyList(), upcoming = tracks.drop(1))
        val removed = mutableListOf<Int>()
        show { QueueContent(state, onJump = {}, onMove = { _, _ -> }, onRemove = { removed += it }) }
        swipe("As I Figure")
        assertEquals(listOf(1), removed)
        assertClosed("As I Figure")
    }

    @Test
    fun `one swipe on a song mark deletes it once, and a row that stays springs back`() {
        val removed = mutableListOf<Int>()
        show {
            ChapterEditorContent(
                ChapterEditorState(listOf(ChapterEditorState.Row(0, "Intro", "0:00"), ChapterEditorState.Row(1, "Wide Open", "1:34"))),
                onTitle = { _, _ -> }, onTime = { _, _ -> }, onStartHere = {}, onDelete = { removed += it }, onAdd = {}, onCancel = {}, onSave = {},
            )
        }
        swipe("Wide Open")
        assertEquals(listOf(1), removed)
        assertClosed("Wide Open")
    }
}
