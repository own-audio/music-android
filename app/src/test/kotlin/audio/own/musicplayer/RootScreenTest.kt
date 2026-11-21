package audio.own.musicplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.home.HomeContent
import audio.own.musicplayer.home.HomeUi
import audio.own.musicplayer.library.HomeContentState
import audio.own.musicplayer.ui.Tab
import audio.own.musicplayer.ui.TabsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun server(id: String) = ServerRecord(id, "own_audio", "Server $id", "https://$id.example.com", ServerAuth.OwnAudio("a@example.com", null))

    @Test
    fun showsTheFiveTabsInOrder() {
        var selected by mutableStateOf(Tab.HOME)
        compose.setContent { TabsScreen(selected, onSelect = { selected = it }) }
        listOf("Home", "Library", "Playlists", "Downloads", "Search").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        compose.onNodeWithText("Search").performClick()
        assertEquals(Tab.SEARCH, selected)
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { TabsScreen(Tab.HOME, onSelect = {}) }
        listOf("Knihovna", "Playlisty", "Stažené", "Hledat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
    }

    @Test
    fun theServerMenuOnlyWithMoreThanOneServer() {
        var servers by mutableStateOf(listOf(server("a")))
        compose.setContent {
            HomeContent(
                ui = HomeUi(content = Load.Loaded(HomeContentState()), servers = servers, activeId = "a"),
                onSelectServer = {}, onOfflineMode = {}, onSettings = {}, onShuffleAll = {}, onRefresh = {}, onRetry = {},
                onAlbum = {}, onPlaylist = {}, onShelf = {},
            )
        }
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        servers = listOf(server("a"), server("b"))
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(1)
    }
}
