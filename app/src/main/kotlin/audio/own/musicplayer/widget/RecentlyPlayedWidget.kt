package audio.own.musicplayer.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * The shelf, "Recently Played" (4×2): the albums and playlists played last as a grid of covers
 * that fills the widget's size; a tap plays one. It keeps the receiver of the 2×2 widget it
 * replaced, so a widget placed before the update becomes this one.
 */
class RecentlyPlayedWidget : MusicWidget() {
    override val sizeMode = SizeMode.Responsive(
        WIDTHS.flatMap { width -> HEIGHTS.map { height -> DpSize(width, height) } }.toSet(),
    )
    override val shelfCovers = true
    override val coverPx = 200

    @Composable
    override fun Content(state: WidgetState) = ShelfContent(state)

    companion object {
        val WIDTHS = listOf(110.dp, 180.dp, 250.dp, 320.dp)
        val HEIGHTS = listOf(100.dp, 180.dp, 260.dp)
    }
}

class RecentlyPlayedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentlyPlayedWidget()
}

/** How many covers fit, how large, and whether there is room for their titles. */
data class ShelfLayout(val columns: Int, val rows: Int, val cover: Dp, val labels: Boolean) {
    companion object {
        private val PADDING = 12.dp
        private val GAP = 8.dp
        private val LABEL = 20.dp
        private val MIN_COVER = 56.dp

        fun of(size: DpSize): ShelfLayout {
            val width = size.width - PADDING * 2
            val height = size.height - PADDING * 2
            val columns = ((width + GAP) / (MIN_COVER + GAP + 8.dp)).toInt().coerceIn(2, 6)
            val cover = (width - GAP * (columns - 1)) / columns
            // With titles, at the width's cover size; without, as many rows as covers of the
            // smallest size allow. The one with more covers wins; titles on a tie.
            val labelled = ((height + GAP) / (cover + LABEL + GAP)).toInt().coerceIn(1, 3)
            val bare = ((height + GAP) / (MIN_COVER + GAP)).toInt().coerceIn(1, 3)
            return when {
                bare > labelled -> ShelfLayout(columns, bare, min(cover, (height - GAP * (bare - 1)) / bare), labels = false)
                height - LABEL >= MIN_COVER -> ShelfLayout(columns, labelled, min(cover, (height - GAP * (labelled - 1)) / labelled - LABEL), labels = true)
                else -> ShelfLayout(columns, 1, min(cover, height), labels = false)
            }
        }
    }
}

@Composable
fun ShelfContent(state: WidgetState) {
    val context = LocalContext.current
    val layout = ShelfLayout.of(LocalSize.current)
    Box(WidgetLook.surface().padding(12.dp), contentAlignment = Alignment.Center) {
        if (state.shelf.isEmpty()) return@Box NothingPlayed(horizontal = false)
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            state.shelf.take(layout.columns * layout.rows).chunked(layout.columns).forEachIndexed { row, entries ->
                if (row > 0) Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    for (column in 0 until layout.columns) {
                        if (column > 0) Spacer(GlanceModifier.width(8.dp))
                        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.TopCenter) {
                            val entry = entries.getOrNull(column) ?: return@Box
                            Column(
                                GlanceModifier.clickable(WidgetActions.open(context, entry))
                                    .semantics { contentDescription = listOf(entry.title, entry.subtitle).filter { it.isNotEmpty() }.joinToString(", ") },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                WidgetCover(entry.cover?.let(state.covers::get), layout.cover, playlist = entry.kind == WidgetSnapshot.Kind.PLAYLIST)
                                if (layout.labels) {
                                    Text(
                                        entry.title,
                                        maxLines = 1,
                                        modifier = GlanceModifier.width(layout.cover).padding(top = 4.dp),
                                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
