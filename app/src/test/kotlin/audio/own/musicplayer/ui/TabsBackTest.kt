package audio.own.musicplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on the emulator: Back on the Playlists tab left the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabsBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `Back on another tab goes Home, and Back on Home leaves the app`() {
        compose.setContent {
            var tab by remember { mutableStateOf(Tab.HOME) }
            TabsScreen(selected = tab, onSelect = { tab = it }) { shown -> Text("content ${shown.name}") }
        }
        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithText("content PLAYLISTS").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("content HOME").assertExists()
        assertFalse(compose.activity.isFinishing)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("on Home, Back is the system's", compose.activity.isFinishing)
    }
}
