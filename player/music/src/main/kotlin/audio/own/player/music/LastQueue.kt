package audio.own.player.music

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import audio.own.player.core.JsonFile
import audio.own.player.core.PlaybackUris
import java.io.File
import kotlinx.serialization.Serializable

/**
 * The queue as it was when the app last played: shown paused in the mini player after a
 * relaunch, and what Media3's playback resumption plays. [tracks] are in play order.
 */
@Serializable
data class LastQueue(
    val tracks: List<Track>,
    val currentTrackId: String?,
    val positionMs: Long,
    val containerId: String? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/** `filesDir/last-queue.json`. */
fun lastQueueStore(file: File): JsonFile<LastQueue> = JsonFile(file, LastQueue.serializer())

/**
 * Builds the `MediaItem` of a song. A downloaded song plays from its file; any other gets a
 * placeholder address ([PlaybackUris]) that the data source resolves when it opens, at the
 * bitrate wanted when the item was built.
 */
class MusicMediaItems(
    private val localFile: (trackId: String) -> Uri? = { null },
    private val artworkUri: (Track) -> Uri? = { null },
) {
    fun localFileOf(trackId: String): Uri? = localFile(trackId)

    fun mediaItem(track: Track, maxBitrateKbps: Int?, title: String = track.title, album: String? = track.album): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(localFile(track.id) ?: PlaybackUris.forItem(track.id, maxBitrateKbps = maxBitrateKbps))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(track.artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri(track))
                    .setDurationMs(track.durationSecs?.let { it * 1000L })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()

    companion object {
        /** A song for an item that came from outside (a controller, Android Auto). */
        fun trackOf(item: MediaItem): Track = Track(
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString(),
            album = item.mediaMetadata.albumTitle?.toString(),
            durationSecs = item.mediaMetadata.durationMs?.let { (it / 1000).toInt() },
        )
    }
}
