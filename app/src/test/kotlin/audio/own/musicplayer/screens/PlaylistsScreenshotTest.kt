package audio.own.musicplayer.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.ServerLibraryRef
import audio.own.kit.ownaudio.SmartRule
import audio.own.music.library.SmartPlaylistInfo
import audio.own.music.library.SmartPresetInfo
import audio.own.music.library.SmartResult
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.library.LibraryUi
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.PlaylistActions
import audio.own.musicplayer.library.PlaylistContent
import audio.own.musicplayer.library.PlaylistPageState
import audio.own.musicplayer.library.PlaylistsContent
import audio.own.musicplayer.library.PlaylistsContentState
import audio.own.musicplayer.library.RuleEditorState
import audio.own.musicplayer.library.SmartDraft
import audio.own.musicplayer.library.SmartPageState
import audio.own.musicplayer.library.SmartPlaylistContent
import audio.own.musicplayer.library.SmartRuleEditorContent
import audio.own.musicplayer.library.SmartTarget
import audio.own.musicplayer.onboarding.QuickConnectContent
import audio.own.musicplayer.onboarding.QuickConnectState
import audio.own.musicplayer.servers.LibraryPicker
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The M5 playlist screens, Quick Connect and the Music Library picker in light, dark, Czech and
 * at twice the font size. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlaylistsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val songs = listOf(
        Song("s|t1", "AcidJazz", artist = "Kevin MacLeod", durationMs = 208_000, playlistEntryId = "e1"),
        Song("s|t2", "Acid Trumpet", artist = "Kevin MacLeod", durationMs = 208_000, playlistEntryId = "e2", isFavorite = true),
        Song("s|t3", "The Run", artist = "Kai Engel", durationMs = 216_000, playlistEntryId = "e3"),
    )
    private val playlist = Playlist("s|p1", "Evening", comment = "For the walk home", owner = "alex", isShared = true, songCount = 3)
    private val smart = SmartPlaylistInfo("s|sp1", "Calm Evenings", SmartRule(), isShared = true, isMine = true, isFrozen = false)
    private val presets = listOf(
        SmartPresetInfo("forgotten-favourites", "Forgotten Favourites", SmartRule()),
        SmartPresetInfo("genre-station", "Genre Station", SmartRule()),
    )

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalLibraryUi provides LibraryUi(currentTrackId = "s|t2", canDownload = true)) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Playlists() = PlaylistsContent(
        state = Load.Loaded(
            PlaylistsContentState(
                smart = listOf(smart),
                presets = presets,
                playlists = listOf(playlist, Playlist("s|p2", "Saturday", owner = "sam", ownerName = "Sam", songCount = 24)),
                hasSmart = true,
            ),
        ),
        username = "alex",
        refreshing = false,
        onBack = null, onRefresh = {}, onRetry = {}, onPlaylist = {}, onSmart = {}, onCreate = {}, onNewSmart = {},
    )

    @Composable
    private fun PlaylistPage() = PlaylistContent(
        state = PlaylistPageState(playlist, Load.Loaded(songs)),
        actions = PlaylistActions(isOwn = true, canChooseCover = true, canShare = true, canReorder = true),
        onBack = {}, onRetry = {}, onRename = {}, onChooseCover = {}, onShare = {}, onDelete = {}, onRemove = {}, onMove = { _, _ -> },
    )

    @Composable
    private fun SmartPage() = SmartPlaylistContent(
        state = SmartPageState(SmartTarget.Saved(smart), Load.Loaded(SmartResult(songs, candidates = 340, durationMs = 632_000))),
        onBack = {}, onReload = {}, onGenre = {}, onClearGenre = {}, onEditRule = {}, onFreeze = {}, onDelete = {}, onSaveAsSmart = {}, onDismissSaved = {},
    )

    @Composable
    private fun Editor() = SmartRuleEditorContent(
        state = RuleEditorState(
            SmartDraft(null, "Calm Evenings", SmartRule(filters = SmartRule.Filters(bpm = listOf(90.0, 130.0), energy = listOf(0.0, 0.4), minRating = 4)), shared = true),
            matching = 128,
        ),
        onCancel = {}, onSave = {}, onName = {}, onShared = {}, onEdit = {},
    )

    @Composable
    private fun QuickConnect() = QuickConnectContent(QuickConnectState(code = "482915"), onCancel = {}, onTryAgain = {})

    @Composable
    private fun Picker() = LibraryPicker(
        Load.Loaded(listOf(ServerLibraryRef("l1", "Music"), ServerLibraryRef("l2", "Vinyl rips"))),
        chosen = "l2",
        onChoose = {}, onDismiss = {}, onRetry = {},
    )

    @Test fun playlistsLight() = shoot("playlists_light", false) { Playlists() }
    @Test fun playlistsDark() = shoot("playlists_dark", true) { Playlists() }
    @Test @Config(qualifiers = "+cs") fun playlistsCzech() = shoot("playlists_cs", false) { Playlists() }
    @Test @Config(fontScale = 2f) fun playlistsLargestText() = shoot("playlists_font200", false) { Playlists() }

    @Test fun playlistLight() = shoot("playlist_light", false) { PlaylistPage() }
    @Test fun playlistDark() = shoot("playlist_dark", true) { PlaylistPage() }
    @Test @Config(qualifiers = "+cs") fun playlistCzech() = shoot("playlist_cs", false) { PlaylistPage() }
    @Test @Config(fontScale = 2f) fun playlistLargestText() = shoot("playlist_font200", false) { PlaylistPage() }

    @Test fun smartLight() = shoot("smart_light", false) { SmartPage() }
    @Test fun smartDark() = shoot("smart_dark", true) { SmartPage() }
    @Test @Config(qualifiers = "+cs") fun smartCzech() = shoot("smart_cs", false) { SmartPage() }
    @Test @Config(fontScale = 2f) fun smartLargestText() = shoot("smart_font200", false) { SmartPage() }

    @Test fun ruleEditorLight() = shoot("rule_editor_light", false) { Editor() }
    @Test fun ruleEditorDark() = shoot("rule_editor_dark", true) { Editor() }
    @Test @Config(qualifiers = "+cs") fun ruleEditorCzech() = shoot("rule_editor_cs", false) { Editor() }
    @Test @Config(fontScale = 2f) fun ruleEditorLargestText() = shoot("rule_editor_font200", false) { Editor() }

    @Test fun quickConnectLight() = shoot("quick_connect_light", false) { QuickConnect() }
    @Test fun quickConnectDark() = shoot("quick_connect_dark", true) { QuickConnect() }
    @Test @Config(qualifiers = "+cs") fun quickConnectCzech() = shoot("quick_connect_cs", false) { QuickConnect() }
    @Test @Config(fontScale = 2f) fun quickConnectLargestText() = shoot("quick_connect_font200", false) { QuickConnect() }

    @Test fun libraryPickerLight() = shoot("library_picker_light", false) { Picker() }
    @Test fun libraryPickerDark() = shoot("library_picker_dark", true) { Picker() }
    @Test @Config(qualifiers = "+cs") fun libraryPickerCzech() = shoot("library_picker_cs", false) { Picker() }
    @Test @Config(fontScale = 2f) fun libraryPickerLargestText() = shoot("library_picker_font200", false) { Picker() }
}
