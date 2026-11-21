package audio.own.musicplayer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsTheFiveTabsInOrder() {
        compose.setContent { RootScreen() }
        listOf("Home", "Library", "Playlists", "Downloads", "Search").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        compose.onNodeWithText("Search").performClick()
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { RootScreen() }
        listOf("Domů", "Knihovna", "Playlisty", "Stažené", "Hledat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
    }
}
