package audio.own.musicplayer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.musicplayer.ui.TabsScreen
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
        compose.setContent { TabsScreen() }
        listOf("Library", "Playlists", "Downloads", "Search").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        // Home is the tab and the bar's title.
        compose.onAllNodesWithText("Home").assertCountEquals(2)
        compose.onNodeWithText("Search").performClick()
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { TabsScreen() }
        listOf("Knihovna", "Playlisty", "Stažené", "Hledat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
    }

    @Test
    fun theServerMenuOnlyWithMoreThanOneServer() {
        var servers by mutableStateOf(listOf(server("a")))
        compose.setContent { TabsScreen(servers = servers, activeId = "a") }
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        servers = listOf(server("a"), server("b"))
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(1)
    }
}
