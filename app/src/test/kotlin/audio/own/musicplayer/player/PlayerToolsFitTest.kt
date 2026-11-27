package audio.own.musicplayer.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.player.core.SleepTimer
import audio.own.player.music.MusicPlayer
import audio.own.player.music.PlayQueue
import audio.own.player.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Found in the Czech screenshots: the fifth tool read "Ekvaliz", cut at the screen's edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayerToolsFitTest {
    @get:Rule val compose = createComposeRule()

    private fun show(canIdentify: Boolean = true) {
        val track = Track("t1", "AcidJazz", "Kevin MacLeod", "Jazz Sampler", 208)
        val state = MusicPlayer.State(currentTrack = track, isPlaying = true, position = PlayQueue.Position(1, 1), played = emptyList(), upcoming = emptyList())
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = true, reduceMotion = true) {
                PlayerContent(
                    ui = PlayerUi(state, SleepTimer.State(), crossfadeOn = true, canIdentify = canIdentify),
                    progress = { Progress(positionMs = 0, durationMs = 208_000, bufferedMs = 0) },
                    onClose = {}, onStop = {}, onToggle = {}, onNext = {}, onPrevious = {}, onSeek = {}, onFavorite = {}, onShuffle = {}, onRepeat = {}, onSheet = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** Every tool on screen shows its whole label on one line, and none sits half off the row. */
    private fun assertLabelsFit(atLeastShown: Int) {
        val viewport = compose.onNodeWithTag("player.tools").fetchSemanticsNode().boundsInRoot
        val labels = compose.onAllNodesWithTag("player.tool.label", useUnmergedTree = true).fetchSemanticsNodes()
        var shown = 0
        for (node in labels) {
            val coordinates = node.layoutInfo.coordinates
            val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
            if (bounds.left >= viewport.right - 0.5f) continue // scrolled to, not on screen
            assertTrue("label at ${bounds.left}..${bounds.right} is cut by the row ending at ${viewport.right}", bounds.right <= viewport.right + 0.5f)
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertEquals("\"${layout.layoutInput.text}\" on one line", 1, layout.lineCount)
            assertFalse("\"${layout.layoutInput.text}\" whole, not clipped", layout.hasVisualOverflow)
            shown++
        }
        assertTrue("$shown tools shown", shown >= atLeastShown)
    }

    @Test @Config(qualifiers = "cs-w360dp-h780dp")
    fun czechOnASmallPhone() { show(); assertLabelsFit(5) }

    @Test @Config(qualifiers = "cs-w360dp-h780dp", fontScale = 1.3f)
    fun czechOnASmallPhoneWithLargerText() { show(); assertLabelsFit(5) }

    @Test @Config(qualifiers = "cs-w411dp-h891dp", fontScale = 1.3f)
    fun czechOnAPhoneWithLargerText() { show(canIdentify = false); assertLabelsFit(5) }

    @Test @Config(qualifiers = "w411dp-h891dp", fontScale = 2f)
    fun englishAtTwiceTheTextSize() { show(); assertLabelsFit(3) }

    @Test @Config(qualifiers = "w800dp-h1280dp")
    fun allToolsInTheTabletColumn() { show(); assertLabelsFit(9) }
}
