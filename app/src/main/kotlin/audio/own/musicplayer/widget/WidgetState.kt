package audio.own.musicplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.ImageProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The song in the player as the widgets show it, read from the app's player when they are drawn. */
data class WidgetPlayer(
    val title: String,
    val artist: String,
    /** The cover's file name in the widget folder; written by [WidgetStore] with the resume entry. */
    val cover: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Everything the three widgets draw. */
data class WidgetState(
    /** What is in the player; null when nothing is (the app was closed, or nothing was played). */
    val player: WidgetPlayer? = null,
    /** Where you left off, offered when [player] is null. */
    val resume: WidgetSnapshot.Entry? = null,
    /** The albums and playlists played last. */
    val shelf: List<WidgetSnapshot.Entry> = emptyList(),
    /** Pictures by file name; one per name, so every size of a widget shares the same bitmap. */
    val covers: Map<String, ImageProvider> = emptyMap(),
)

/**
 * Reads the widgets' state: the snapshot file for the shelf and where you left off, the live player
 * for what plays. [version] goes up on every redraw, so a widget whose session is still running
 * reads again instead of drawing what it read last.
 */
object WidgetData {
    val version = MutableStateFlow(0L)

    fun folder(context: Context) = File(context.filesDir, "widget")

    suspend fun load(context: Context, player: WidgetPlayer?, shelfCovers: Boolean, coverPx: Int): WidgetState = withContext(Dispatchers.IO) {
        val store = WidgetStore(folder(context))
        val entries = runCatching { store.load()?.entries }.getOrNull().orEmpty()
        val resume = entries.firstOrNull { it.kind == WidgetSnapshot.Kind.RESUME }
        val shelf = entries.filter { it.kind != WidgetSnapshot.Kind.RESUME }
        val names = buildSet {
            (player?.cover ?: resume?.cover)?.let(::add)
            if (shelfCovers) shelf.mapNotNullTo(this) { it.cover }
        }
        val covers = names.mapNotNull { name -> decode(store.cover(name), coverPx)?.let { name to ImageProvider(it) } }.toMap()
        WidgetState(player, if (player == null) resume else null, shelf, covers)
    }

    /**
     * A picture no larger than [px] across: a home screen holds every widget's bitmaps in one
     * parcel with a size limit, so they are not decoded at the file's size when smaller will do.
     */
    fun decode(file: File, px: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (bitmap.width <= px) bitmap else Bitmap.createScaledBitmap(bitmap, px, bitmap.height * px / bitmap.width, true)
        }.getOrNull()
    }
}
