package audio.own.musicplayer.widget

import android.net.Uri
import audio.own.core.servers.CompositeId
import audio.own.music.library.model.Album
import audio.own.musicplayer.platform.MusicLinks
import audio.own.player.core.JsonFile
import audio.own.player.music.Track
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * What the widgets show besides the song playing: where you left off and the albums played last.
 * The app writes it to `filesDir/widget/` whenever its entries change, the widgets only read. Pictures sit next to it as small JPEGs, so the widget
 * needs neither the network nor a sign-in. Ported from the iOS app's `WidgetSnapshot`.
 */
@Serializable
data class WidgetSnapshot(val entries: List<Entry>, val atEpochMs: Long = 0) {
    enum class Kind { RESUME, ALBUM, PLAYLIST }

    @Serializable
    data class Entry(
        /** The album or playlist as a composite id; the song for [Kind.RESUME]. */
        val id: String,
        val kind: Kind,
        val title: String,
        /** The artist, or for [Kind.RESUME] the song's artist. */
        val subtitle: String,
        /** The picture's file name in the widget's folder, or null. */
        val cover: String? = null,
    ) {
        val link: Uri
            get() = when (kind) {
                Kind.RESUME -> MusicLinks.resume()
                Kind.ALBUM -> MusicLinks.play(MusicLinks.Kind.ALBUM, id)
                Kind.PLAYLIST -> MusicLinks.play(MusicLinks.Kind.PLAYLIST, id)
            }
    }

    /** An entry with the cover it shows, before the picture is on the device. */
    data class Picked(val entry: Entry, val coverId: String?)

    companion object {
        /** The shelf's covers at most: three rows of four on a large widget. */
        const val SHELF_MAX = 12

        /** How many recently played albums are asked for. */
        const val ALBUMS_ASKED = SHELF_MAX

        /**
         * Where you left off ([current], the song in the player or the last queue's), then the
         * albums played last on the active server, for the shelf.
         */
        fun pick(current: Track?, albums: List<Picked>, activeServerId: String?): List<Picked> {
            val resume = current?.let { track ->
                Picked(Entry(track.id, Kind.RESUME, track.title, track.artist.orEmpty(), track.artworkId?.let(::coverName)), track.artworkId)
            }
            val recent = albums.filter { CompositeId.split(it.entry.id)?.first == activeServerId }.take(SHELF_MAX)
            return listOfNotNull(resume) + recent
        }

        fun album(album: Album): Picked {
            val coverId = album.coverArt ?: album.id
            return Picked(Entry(album.id, Kind.ALBUM, album.name, album.artist.orEmpty(), coverName(coverId)), coverId)
        }

        /** The `@2` names the larger pictures of the redesigned widgets, so the small old ones are made again. */
        fun coverName(coverId: String): String =
            MessageDigest.getInstance("SHA-1").digest("$coverId@2".toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg"
    }
}

/** The snapshot's file and its pictures, in [folder] (`filesDir/widget`). */
class WidgetStore(private val folder: File) {
    private val file = JsonFile(File(folder, "snapshot.json"), WidgetSnapshot.serializer())

    fun load(): WidgetSnapshot? = file.read()

    fun cover(name: String): File = File(folder, name)

    /**
     * Writes the entries when they differ from the file's, with the pictures they name that
     * aren't there yet ([jpeg] makes one from a cover id) and without the ones no entry names any
     * more. Returns whether anything was written, so the widget is redrawn only then.
     */
    suspend fun save(picked: List<WidgetSnapshot.Picked>, atEpochMs: Long, jpeg: suspend (String) -> ByteArray?): Boolean {
        val entries = picked.map { pick ->
            val name = pick.entry.cover ?: return@map pick.entry
            val target = cover(name)
            if (target.exists()) return@map pick.entry
            val bytes = pick.coverId?.let { jpeg(it) } ?: return@map pick.entry.copy(cover = null)
            folder.mkdirs()
            val partial = File(folder, "$name.part")
            partial.writeBytes(bytes)
            partial.renameTo(target)
            pick.entry
        }
        if (load()?.entries == entries) return false
        folder.mkdirs()
        file.write(WidgetSnapshot(entries, atEpochMs))
        val kept = entries.mapNotNull { it.cover }.toSet()
        folder.listFiles { f -> f.name.endsWith(".jpg") && f.name !in kept }?.forEach { it.delete() }
        return true
    }
}
