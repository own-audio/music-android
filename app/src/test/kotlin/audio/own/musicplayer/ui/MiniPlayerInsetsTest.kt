package audio.own.musicplayer.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Found on a tablet: with the rail, the mini player sat under the gesture handle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniPlayerInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val navBar = 48.dp

    private fun show() {
        compose.setContent {
            TabsScreen(
                selected = Tab.HOME,
                onSelect = {},
                above = { Box(Modifier.fillMaxWidth().height(64.dp).testTag("mini")) },
            ) { Text("content") }
        }
        val px = (navBar.value * compose.activity.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `with the rail, the mini player clears the navigation bar`() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val mini = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        val barTop = root.bottom - navBar.value * compose.density.density
        assertTrue("mini player bottom ${mini.bottom} must be above the bar at $barTop", mini.bottom <= barTop + 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `with the bottom bar, the mini player sits right on the tabs, not an inset higher`() {
        show()
        val mini = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        val tabsTop = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
            .minOf { it.boundsInRoot.top }
        val gap = tabsTop - mini.bottom
        assertTrue("gap $gap between mini player and tabs must be less than the inset", gap >= -0.5f && gap < navBar.value * compose.density.density)
    }
}
