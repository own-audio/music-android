@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import audio.own.core.designsystem.theme.AppBrand
import audio.own.musicplayer.MainActivity
import audio.own.musicplayer.R
import java.io.File

/**
 * The home-screen widget "Recently Played": where you left off and the albums played last, a tap
 * away; one on the small size, three on the medium. Everything comes from the snapshot the app
 * writes ([WidgetStore]); a tap opens the app and plays it. Ported from the iOS app's widget.
 */
class RecentlyPlayedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetStore(folder(context))
        val entries = runCatching { store.load()?.entries }.getOrNull().orEmpty()
        val covers = entries.mapNotNull { entry -> entry.cover?.let { name -> decode(store.cover(name))?.let { name to it } } }.toMap()
        provideContent { RecentlyPlayedContent(entries, covers) }
    }

    private fun decode(file: File): Bitmap? = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)

        fun folder(context: Context) = File(context.filesDir, "widget")

        val accent = ColorProvider(day = AppBrand.accentLight, night = AppBrand.accentDark)
        val accentSoft = ColorProvider(day = AppBrand.accentLight.copy(alpha = 0.15f), night = AppBrand.accentDark.copy(alpha = 0.15f))
        val background = ColorProvider(day = Color(0xFFF7F7F8), night = Color(0xFF0C0C0E))
        val foreground = ColorProvider(day = Color(0xFF1D1D1F), night = Color(0xFFF5F5F7))
        val muted = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFF98989D))
    }
}

class RecentlyPlayedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentlyPlayedWidget()
}

@Composable
private fun RecentlyPlayedContent(entries: List<WidgetSnapshot.Entry>, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    val medium = LocalSize.current.width >= RecentlyPlayedWidget.MEDIUM.width
    Box(GlanceModifier.fillMaxSize().background(RecentlyPlayedWidget.background).padding(12.dp)) {
        when {
            entries.isEmpty() -> Column(
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(ImageProvider(R.drawable.ic_widget_note), contentDescription = null, colorFilter = ColorFilter.tint(RecentlyPlayedWidget.accent), modifier = GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.height(6.dp))
                Text(context.getString(R.string.widget_nothing_played), style = TextStyle(color = RecentlyPlayedWidget.muted, fontSize = 12.sp, textAlign = TextAlign.Center))
            }
            !medium -> Small(entries.first(), covers)
            else -> Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                entries.take(WidgetSnapshot.MAX_ENTRIES).forEachIndexed { index, entry ->
                    if (index > 0) Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open(context, entry))), verticalAlignment = Alignment.CenterVertically) {
                        Cover(entry, covers[entry.cover], 40)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(entry.title, maxLines = 1, style = TextStyle(color = RecentlyPlayedWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                            Text(label(context, entry), maxLines = 1, style = TextStyle(color = RecentlyPlayedWidget.muted, fontSize = 11.sp))
                        }
                        Spacer(GlanceModifier.width(8.dp))
                        Image(ImageProvider(R.drawable.ic_widget_play), contentDescription = null, colorFilter = ColorFilter.tint(RecentlyPlayedWidget.accent), modifier = GlanceModifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Small(entry: WidgetSnapshot.Entry, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(open(context, entry)))) {
        Cover(entry, covers[entry.cover], 56)
        Spacer(GlanceModifier.defaultWeight())
        Text(entry.title, maxLines = 2, style = TextStyle(color = RecentlyPlayedWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Text(label(context, entry), maxLines = 1, style = TextStyle(color = RecentlyPlayedWidget.muted, fontSize = 11.sp))
    }
}

@Composable
private fun Cover(entry: WidgetSnapshot.Entry, bitmap: Bitmap?, size: Int) {
    if (bitmap != null) {
        Image(ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size.dp).cornerRadius(8.dp))
    } else {
        val icon = if (entry.kind == WidgetSnapshot.Kind.PLAYLIST) R.drawable.ic_widget_playlist else R.drawable.ic_widget_note
        Box(GlanceModifier.size(size.dp).cornerRadius(8.dp).background(RecentlyPlayedWidget.accentSoft), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), contentDescription = null, colorFilter = ColorFilter.tint(RecentlyPlayedWidget.accent), modifier = GlanceModifier.size((size / 2).dp))
        }
    }
}

private fun label(context: Context, entry: WidgetSnapshot.Entry): String =
    if (entry.kind == WidgetSnapshot.Kind.RESUME) context.getString(R.string.widget_continue, entry.subtitle) else entry.subtitle

private fun open(context: Context, entry: WidgetSnapshot.Entry): Intent =
    Intent(Intent.ACTION_VIEW, entry.link).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
