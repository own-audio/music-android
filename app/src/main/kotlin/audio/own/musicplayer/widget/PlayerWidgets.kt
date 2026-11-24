package audio.own.musicplayer.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import audio.own.musicplayer.R
import audio.own.core.strings.R as SR

/** The player strip (4×1): cover, song and artist, previous, play/pause and next. */
class PlayerStripWidget : MusicWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(TINY, ROW, TALL_NARROW, TALL))
    override val shelfCovers = false
    override val coverPx = 320

    @Composable
    override fun Content(state: WidgetState) = PlayerStripContent(state)

    companion object {
        val TINY = DpSize(110.dp, 40.dp)
        val ROW = DpSize(220.dp, 40.dp)

        /** Stretched taller, the strip becomes the big player rather than a lonely row. */
        val TALL_NARROW = DpSize(110.dp, 110.dp)
        val TALL = DpSize(220.dp, 110.dp)
    }
}

class PlayerStripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerStripWidget()
}

/** The big player (4×2): a large cover, song, artist, progress and the controls; Continue when nothing plays. */
class NowPlayingWidget : MusicWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, WIDE_TALL, LARGE, LARGE_TALL))
    override val shelfCovers = false
    override val coverPx = 320

    @Composable
    override fun Content(state: WidgetState) = NowPlayingContent(state)

    companion object {
        val NARROW = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(220.dp, 110.dp)
        val WIDE_TALL = DpSize(220.dp, 170.dp)
        val LARGE = DpSize(300.dp, 140.dp)
        val LARGE_TALL = DpSize(300.dp, 220.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** What either player shows: the song playing, or where you left off. */
private data class Showing(
    val title: String,
    val subtitle: String,
    val cover: ImageProvider?,
    val isPlaying: Boolean,
    val progress: Float?,
    /** Something is in the player: pause, previous and next work. */
    val loaded: Boolean,
)

private fun WidgetState.showing(): Showing? = when {
    player != null -> Showing(player.title, player.artist, player.cover?.let(covers::get), player.isPlaying, player.progress, loaded = true)
    resume != null -> Showing(resume.title, resume.subtitle, resume.cover?.let(covers::get), isPlaying = false, progress = null, loaded = false)
    else -> null
}

@Composable
fun PlayerStripContent(state: WidgetState) {
    val size = LocalSize.current
    if (size.height >= PlayerStripWidget.TALL_NARROW.height) return NowPlayingContent(state)
    val context = LocalContext.current
    val showing = state.showing()
    Row(WidgetLook.surface().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showing == null) return@Row NothingPlayed(horizontal = true)
        val open = if (showing.loaded) WidgetActions.openPlayer(context) else state.resume?.let { WidgetActions.open(context, it) } ?: WidgetActions.openApp(context)
        Row(GlanceModifier.defaultWeight().fillMaxHeight().clickable(open).semantics { testTag = "open" }, verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(showing.cover, min(size.height - 16.dp, 56.dp).coerceAtLeast(32.dp))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                if (!showing.loaded) WidgetEyebrow(context.getString(R.string.widget_continue_label))
                WidgetTitle(showing.title)
                if (showing.subtitle.isNotEmpty()) WidgetSubtitle(showing.subtitle)
            }
        }
        Spacer(GlanceModifier.width(4.dp))
        Controls(showing, withSkips = size.width >= PlayerStripWidget.ROW.width, spread = false)
    }
}

@Composable
fun NowPlayingContent(state: WidgetState) {
    val size = LocalSize.current
    val context = LocalContext.current
    val showing = state.showing()
    Box(WidgetLook.surface().padding(14.dp)) {
        if (showing == null) return@Box NothingPlayed(horizontal = false)
        val open = if (showing.loaded) WidgetActions.openPlayer(context) else state.resume?.let { WidgetActions.open(context, it) } ?: WidgetActions.openApp(context)
        if (size.width < NowPlayingWidget.WIDE.width) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth()) {
                    WidgetCover(showing.cover, min(size.width - 28.dp - 60.dp, size.height / 2), modifier = GlanceModifier.clickable(open))
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetPlayButton(showing.isPlaying, playOrPause(context, showing), playLabel(context, showing))
                }
                Spacer(GlanceModifier.defaultWeight())
                Column(GlanceModifier.fillMaxWidth().clickable(open).semantics { testTag = "open" }) {
                    if (!showing.loaded) WidgetEyebrow(context.getString(R.string.widget_continue_label))
                    WidgetTitle(showing.title)
                    if (showing.subtitle.isNotEmpty()) WidgetSubtitle(showing.subtitle)
                }
            }
            return@Box
        }
        val cover: Dp = min(min(size.height - 28.dp, size.width * 0.42f), 180.dp)
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(showing.cover, cover, modifier = GlanceModifier.clickable(open))
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(open).semantics { testTag = "open" }) {
                    if (!showing.loaded) WidgetEyebrow(context.getString(R.string.widget_continue_label))
                    WidgetTitle(showing.title, size = 16.sp, maxLines = if (size.height >= NowPlayingWidget.WIDE_TALL.height) 2 else 1)
                    if (showing.subtitle.isNotEmpty()) WidgetSubtitle(showing.subtitle, size = 14.sp)
                }
                if (showing.progress != null) {
                    WidgetProgress(showing.progress)
                    Spacer(GlanceModifier.height(4.dp))
                }
                Controls(showing, withSkips = true, spread = true)
            }
        }
    }
}

@Composable
private fun Controls(showing: Showing, withSkips: Boolean, spread: Boolean) {
    val context = LocalContext.current
    Row(if (spread) GlanceModifier.fillMaxWidth() else GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
        if (withSkips) {
            WidgetControl(WidgetLook.icons.previous, context.getString(R.string.previous), WidgetActions.command(WidgetActions.Command.PREVIOUS).takeIf { showing.loaded }, tag = "previous")
            if (spread) Spacer(GlanceModifier.defaultWeight())
        }
        WidgetPlayButton(showing.isPlaying, playOrPause(context, showing), playLabel(context, showing))
        if (withSkips) {
            if (spread) Spacer(GlanceModifier.defaultWeight())
            WidgetControl(WidgetLook.icons.next, context.getString(R.string.next), WidgetActions.command(WidgetActions.Command.NEXT).takeIf { showing.loaded }, tag = "next")
        }
    }
}

private fun playOrPause(context: android.content.Context, showing: Showing): Action =
    if (showing.isPlaying) WidgetActions.command(WidgetActions.Command.PAUSE) else WidgetActions.play(context)

private fun playLabel(context: android.content.Context, showing: Showing): String =
    context.getString(if (showing.isPlaying) SR.string.pause else SR.string.play)

/** Nothing played yet: the app's note and a word; a tap opens the app. */
@Composable
fun NothingPlayed(horizontal: Boolean) {
    val context = LocalContext.current
    val icon = @Composable {
        Image(ImageProvider(R.drawable.ic_widget_note), contentDescription = null, colorFilter = ColorFilter.tint(WidgetLook.accent), modifier = GlanceModifier.size(28.dp))
    }
    val text = @Composable { modifier: GlanceModifier ->
        Text(context.getString(R.string.widget_nothing_played), modifier = modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, textAlign = if (horizontal) TextAlign.Start else TextAlign.Center))
    }
    val modifier = GlanceModifier.fillMaxSize().clickable(WidgetActions.openApp(context))
    if (horizontal) {
        Row(modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(GlanceModifier.width(12.dp))
            text(GlanceModifier.defaultWeight())
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(GlanceModifier.height(8.dp))
            text(GlanceModifier)
        }
    }
}
