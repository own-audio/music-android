package audio.own.musicplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import audio.own.core.designsystem.theme.AppBrand
import audio.own.music.library.MusicSource
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A cover named by the id a model carries in `coverArt` ("<server>|<record>~<image>"), at a
 * size in pixels. Coil caches it by that id, never by the link: own.audio signs image links and
 * the signature changes (docs/android-foundation.md §7.3).
 */
data class CoverRef(val id: String, val sizePx: Int) {
    /** The memory and disk cache key: one entry per id and size step. */
    val key: String get() = "$id@$sizePx"

    companion object {
        /** The sizes the server makes; nearer ones share an entry. */
        private val STEPS = listOf(128, 256, 512, 1024)

        fun of(id: String, pixels: Int): CoverRef = CoverRef(id, STEPS.firstOrNull { it >= pixels } ?: STEPS.last())

        /** Every cache key a cover can have, one per size step. */
        fun keys(id: String): List<String> = STEPS.map { CoverRef(id, it).key }
    }
}

/** Finds the link of a cover through its server's source, and fetches it with the bare client. */
class CoverFetcher(
    private val cover: CoverRef,
    private val loader: ImageLoader,
    private val sourceOf: (String) -> MusicSource?,
    private val http: OkHttpClient,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val disk = loader.diskCache
        disk?.openSnapshot(cover.key)?.let { return fromDisk(disk, it, DataSource.DISK) }
        val source = sourceOf(cover.id) ?: throw IOException("No server for ${cover.id}")
        val link = source.coverLink(cover.id, cover.sizePx)
        val bytes = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(link).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for a cover")
                response.body.bytes()
            }
        }
        if (disk != null) {
            disk.openEditor(cover.key)?.let { editor ->
                try {
                    disk.fileSystem.write(editor.data) { write(bytes) }
                    editor.commitAndOpenSnapshot()?.let { return fromDisk(disk, it, DataSource.NETWORK) }
                } catch (error: Exception) {
                    editor.abort()
                }
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), null, DataSource.NETWORK)
    }

    private fun fromDisk(disk: DiskCache, snapshot: DiskCache.Snapshot, from: DataSource) =
        SourceFetchResult(ImageSource(snapshot.data, disk.fileSystem, cover.key, snapshot), null, from)

    class Factory(private val sourceOf: (String) -> MusicSource?, private val http: OkHttpClient) : Fetcher.Factory<CoverRef> {
        override fun create(data: CoverRef, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, imageLoader, sourceOf, http)
    }

    object Key : Keyer<CoverRef> {
        override fun key(data: CoverRef, options: Options): String = data.key
    }
}

object Covers {
    fun imageLoader(context: Context, sourceOf: (String) -> MusicSource?, http: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(CoverFetcher.Key)
                add(CoverFetcher.Factory(sourceOf, http))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()

    /** The notification's and lock screen's picture, about 600 px. */
    suspend fun bitmap(context: Context, loader: ImageLoader, coverId: String, pixels: Int = 600): Bitmap? {
        val request = ImageRequest.Builder(context).data(CoverRef.of(coverId, pixels)).allowHardware(false).build()
        val result = loader.execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    /**
     * A picture changed on the server: its cached copies go, and covers drawn by these ids load
     * again. An id names a record, so its link now leads to the new picture.
     */
    fun forget(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val loader = coil3.SingletonImageLoader.get(context)
        val keys = ids.flatMap(CoverRef::keys).toSet()
        loader.diskCache?.let { disk -> keys.forEach(disk::remove) }
        loader.memoryCache?.let { memory -> memory.keys.filter { it.key in keys }.forEach(memory::remove) }
        CoverChanges.bump(ids)
    }

    /** The artwork address of a song's `MediaItem`: names the cover, holds no signed link. */
    fun artworkUri(coverId: String): Uri = Uri.Builder().scheme(SCHEME).authority("cover").appendQueryParameter("id", coverId).build()

    fun coverIdOf(uri: Uri): String? = if (uri.scheme == SCHEME) uri.getQueryParameter("id") else null

    private const val SCHEME = "ownaudio-cover"
}

/** How often each cover changed here: a new count makes the covers drawn by that id load again. */
object CoverChanges {
    private val versions = mutableStateMapOf<String, Int>()

    fun version(id: String): Int = versions[id] ?: 0

    fun bump(ids: Collection<String>) {
        for (id in ids) versions[id] = version(id) + 1
    }
}

/** The Coil request of a cover drawn at [size]: what `CoverArt` takes as its model. */
@Composable
fun rememberCover(coverId: String?, size: Dp): Any? {
    if (coverId == null) return null
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val version = CoverChanges.version(coverId)
    return remember(coverId, pixels, version) {
        ImageRequest.Builder(context).data(CoverRef.of(coverId, pixels)).crossfade(AppBrand.COVER_CROSSFADE_MS).build()
    }
}

/**
 * Fetches a downloaded collection's cover into the disk cache at every size the screens draw
 * (list rows, the album page, the player, the lock screen), so it shows offline too.
 */
fun prefetchCover(context: android.content.Context, coverId: String?) {
    if (coverId == null) return
    val loader = coil3.SingletonImageLoader.get(context)
    for (pixels in listOf(120, 300, 600, 1000)) {
        loader.enqueue(ImageRequest.Builder(context).data(CoverRef.of(coverId, pixels)).build())
    }
}
