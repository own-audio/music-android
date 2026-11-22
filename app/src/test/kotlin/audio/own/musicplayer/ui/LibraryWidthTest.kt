package audio.own.musicplayer.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Found on the emulator: the Library tab open on a phone-sized window, then the window made
 * tablet-sized: "Restoring the Navigation back stack failed", and the app closed. The wide
 * Library starts on Artists beside a sidebar; the narrow one on the section grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the Library tab survives the window becoming wide and narrow again`() {
        // Read only when the screen is made again, as after an activity is recreated for its new size.
        var wide = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val nav = rememberTabNavs(wide).getValue(Tab.LIBRARY)
            if (wide) {
                NavHost(nav, startDestination = "artists") {
                    composable("artists") { Text("wide artists") }
                    composable("album") { Text("wide album") }
                }
            } else {
                NavHost(nav, startDestination = "library") {
                    composable("library") { Text("sections") }
                    composable("albums") { Text("albums") }
                }
            }
            if (!wide && nav.currentDestination?.route == "library") androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate("albums") }
        }
        compose.onNodeWithText("albums").assertExists()
        wide = true
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("wide artists").assertExists()
        wide = false
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("albums").assertExists()
    }
}
