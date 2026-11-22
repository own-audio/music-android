package audio.own.musicplayer.servers

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.musicplayer.settings.AppLanguage
import audio.own.musicplayer.settings.SettingsContent
import audio.own.musicplayer.settings.SettingsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the emulator test of 2026-11-22 found on the server pages and in Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class EmulatorFindingsTest {
    @get:Rule val compose = createComposeRule()

    private val navidrome = ServerRecord(
        id = "n1", kind = ServerKinds.SUBSONIC, displayName = "Navidrome", host = "https://music.example.com",
        auth = ServerAuth.Subsonic("alex", "secret"),
    )
    private val jellyfin = navidrome.copy(id = "j1", kind = ServerKinds.JELLYFIN, displayName = "Jellyfin")
    private val ownAudio = navidrome.copy(id = "o1", kind = ServerKinds.OWN_AUDIO, displayName = "own.audio", auth = ServerAuth.OwnAudio("alex@example.com", StoredTokens("a", "r", 0)))

    private fun page(server: ServerRecord) = compose.setContent {
        OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
            ServerDetailContent(
                ui = ServerPageUi(server = server, isActive = true, connection = null, check = null, checking = false, refused = false, detail = ServerDetailState()),
                onBack = {}, onUse = {}, onCheckNow = {}, onSignInAgain = {}, onDeleteAccount = {}, onFamily = {}, onDevices = {}, onListening = {},
                onRename = {}, onConnection = {}, onAddAddress = {}, onRemoveAddress = {}, onRemove = {}, onDismissError = {},
            )
        }
    }

    /** A Subsonic server's page said the server tells the app its addresses; Subsonic has no way to. */
    @Test
    fun `a Subsonic server's page asks for its addresses instead of claiming the server sends them`() {
        page(navidrome)
        compose.onNodeWithText("Subsonic servers don't say which addresses they have", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("The server tells the app its addresses", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a Jellyfin server's page still says the server sends its addresses`() {
        page(jellyfin)
        compose.onNodeWithText("The server tells the app its addresses", substring = true).assertExists()
    }

    /** A second own.audio server was listed last, after Navidrome and Jellyfin; iOS lists own.audio first. */
    @Test
    fun `own audio servers come first in Settings, the others as they were added`() {
        assertEquals(listOf("o1", "n1", "j1"), ServersState.inOrder(listOf(navidrome, jellyfin, ownAudio)).map { it.id })
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                SettingsContent(
                    ui = SettingsUi(listOf(navidrome, jellyfin, ownAudio), activeId = "n1", accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1"),
                    onBack = {}, onOpenServer = {}, onRemoveServer = {}, onAddServer = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
                )
            }
        }
        val tops = listOf("own.audio", "Navidrome", "Jellyfin").map { compose.onAllNodesWithText(it)[0].fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }
}
