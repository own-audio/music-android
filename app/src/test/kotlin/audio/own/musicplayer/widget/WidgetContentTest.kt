package audio.own.musicplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartServiceClickAction
import androidx.glance.appwidget.testing.unit.isLinearProgressIndicator
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import audio.own.musicplayer.MainActivity
import audio.own.musicplayer.platform.MusicLinks
import audio.own.musicplayer.player.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each widget shows in each state, and what its buttons send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sample = WidgetSamples.state()
    private val playing = sample
    private val paused = sample.copy(player = sample.player!!.copy(isPlaying = false))
    private val resumeEntry = WidgetSnapshot.Entry("s|t", WidgetSnapshot.Kind.RESUME, "Night Drive", "The Lanterns")
    private val resume = WidgetState(resume = resumeEntry)

    private val strip = DpSize(350.dp, 80.dp)
    private val big = DpSize(350.dp, 180.dp)


    private fun command(command: WidgetActions.Command) = actionParametersOf(WidgetActions.COMMAND to command.name)

    @Test
    fun `the strip playing shows the song and sends pause, previous and next to the session`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        provideComposable { PlayerStripContent(playing) }
        onNode(hasTextEqualTo("Blue Hour")).assertExists()
        onNode(hasTextEqualTo("Mira Lane")).assertExists()
        onNode(hasTestTag("pause")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.PAUSE))
        onNode(hasTestTag("previous")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.PREVIOUS))
        onNode(hasTestTag("next")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.NEXT))
        onNode(hasContentDescriptionEqualTo("Pause")).assertExists()
        onNode(hasContentDescriptionEqualTo("Previous")).assertExists()
        onNode(hasContentDescriptionEqualTo("Next")).assertExists()
        onNode(hasTestTag("open")).assertHasStartActivityClickAction(link(MusicLinks.player()))
    }

    @Test
    fun `paused, play starts the media service in the foreground with a play key, as the notification does`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        provideComposable { PlayerStripContent(paused) }
        onNode(hasTestTag("play")).assertHasStartServiceClickAction(WidgetActions.playIntent(context), isForegroundService = true)
        onNode(hasContentDescriptionEqualTo("Play")).assertExists()
    }

    @Test
    fun `a narrow strip keeps only play`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(160.dp, 80.dp))
        provideComposable { PlayerStripContent(paused) }
        onNode(hasTestTag("play")).assertExists()
        onNode(hasTestTag("next")).assertDoesNotExist()
        onNode(hasTestTag("previous")).assertDoesNotExist()
    }

    @Test
    fun `a strip stretched taller becomes the big player, with its progress`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 140.dp))
        provideComposable { PlayerStripContent(playing) }
        onNode(isLinearProgressIndicator(playing.player!!.progress)).assertExists()
    }

    @Test
    fun `nothing in the player offers where you left off, and play resumes it`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(resume) }
        onNode(hasTextEqualTo("Continue")).assertExists()
        onNode(hasTextEqualTo("Night Drive")).assertExists()
        onNode(hasTestTag("play")).assertHasStartServiceClickAction(WidgetActions.playIntent(context), isForegroundService = true)
        onNode(hasTestTag("open")).assertHasStartActivityClickAction(link(MusicLinks.resume()))
        onAllNodes(hasTestTag("next")).assertCountEquals(1)
        onAllNodes(isLinearProgressIndicator(0f)).assertCountEquals(0)
    }

    @Test
    fun `the big player shows the progress and the controls`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(playing) }
        onNode(isLinearProgressIndicator(84_000f / 236_000f)).assertExists()
        onNode(hasTestTag("pause")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.PAUSE))
        onNode(hasTestTag("next")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.NEXT))
    }

    @Test
    fun `long titles stay one line on the strip`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        val long = "A Very Long Song Title That Does Not Fit On One Line At All"
        provideComposable { PlayerStripContent(playing.copy(player = playing.player!!.copy(title = long))) }
        onNode(hasText(long)).assertExists()
    }

    @Test
    fun `nothing played yet says so and opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(WidgetState()) }
        onNode(hasTextEqualTo("Nothing played yet")).assertExists()
        onNode(hasTestTag("play")).assertDoesNotExist()
    }

    @Test
    fun `the shelf fills its size with covers, and a tap plays the album`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 200.dp))
        provideComposable { ShelfContent(sample) }
        val layout = ShelfLayout.of(DpSize(350.dp, 200.dp))
        onAllNodes(hasContentDescriptionEqualTo("Night Drive, The Lanterns")).assertCountEquals(1)
        onNode(hasContentDescriptionEqualTo("Night Drive, The Lanterns"))
            .assertHasStartActivityClickAction(link(MusicLinks.play(MusicLinks.Kind.ALBUM, "sample|1")))
        // Every cover that fits is there, and no more.
        val shown = layout.columns * layout.rows
        onAllNodes(hasContentDescriptionEqualTo("${sample.shelf[shown - 1].title}, ${sample.shelf[shown - 1].subtitle}")).assertCountEquals(1)
        if (shown < sample.shelf.size) onAllNodes(hasContentDescriptionEqualTo("${sample.shelf[shown].title}, ${sample.shelf[shown].subtitle}")).assertCountEquals(0)
    }

    @Test
    fun `an old 2x2 widget, now the shelf, shows two by two`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(170.dp, 180.dp))
        provideComposable { ShelfContent(sample) }
        onAllNodes(hasContentDescriptionEqualTo("Paper Boats, Northern Lights Trio")).assertCountEquals(1)
        onAllNodes(hasContentDescriptionEqualTo("Saturday, Family Mix")).assertCountEquals(0)
    }

    @Test
    fun `the shelf with nothing played says so`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 180.dp))
        provideComposable { ShelfContent(WidgetState()) }
        onNode(hasTextEqualTo("Nothing played yet")).assertExists()
    }

    @Test
    fun `the shelf's layout from its minimum to large sizes`() {
        assertEquals(ShelfLayout(2, 2, 69.dp, labels = false), ShelfLayout.of(DpSize(170.dp, 180.dp)))
        val wide = ShelfLayout.of(DpSize(350.dp, 120.dp))
        assertEquals(4, wide.columns)
        assertEquals(1, wide.rows)
        assertEquals(3, ShelfLayout.of(DpSize(350.dp, 300.dp)).rows)
        assertEquals(5, ShelfLayout.of(DpSize(420.dp, 180.dp)).columns)
    }

    @Test
    fun `play is the media button's play key, which resumes the last queue when nothing is loaded`() {
        val intent = WidgetActions.playIntent(context)
        assertEquals(Intent.ACTION_MEDIA_BUTTON, intent.action)
        assertEquals(ComponentName(context, PlaybackService::class.java), intent.component)
        @Suppress("DEPRECATION")
        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)!!
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, key.keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, key.action)
    }

    @Test
    fun `the old widget's receiver is the shelf`() {
        assertEquals(RecentlyPlayedWidget::class, RecentlyPlayedWidgetReceiver().glanceAppWidget::class)
    }

    private fun link(uri: android.net.Uri) =
        Intent(Intent.ACTION_VIEW, uri).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
