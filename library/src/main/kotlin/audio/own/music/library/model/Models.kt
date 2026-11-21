package audio.own.music.library.model

import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// One set of models for every kind of server. Every id is composite, "<server id>|<item id>"
// (audio.own.core.servers.CompositeId), so one queue, one download list and one cover cache can
// mix servers. A cover is named by the id the source hands out for it (MusicSource.coverLink).
// Times are milliseconds, as Media3 and own.audio's API count them.

data class Artist(
    val id: String,
    val name: String,
    /** Not every listing sends it; null shows no count. */
    val albumCount: Int? = null,
    /** An image id for MusicSource.coverLink, where the server keeps artist pictures itself. */
    val coverArt: String? = null,
    /** A ready image link, where the server hands one out instead (Navidrome does). */
    val imageUrl: String? = null,
    val isFavorite: Boolean = false,
) {
    /**
     * [imageUrl] asked for at [size] pixels when the link carries a `size` parameter (Navidrome's
     * do); otherwise the link as it is.
     */
    fun imageUrl(size: Int): String? {
        val link = imageUrl ?: return null
        val url = link.toHttpUrlOrNull() ?: return link
        if (url.queryParameter("size") == null) return link
        // Rebuilt in the same order: some servers sign the query as it was handed out.
        val builder = url.newBuilder().query(null)
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            builder.addQueryParameter(name, if (name == "size") size.toString() else url.queryParameterValue(i))
        }
        return builder.build().toString()
    }
}

data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int = 0,
    val durationMs: Long? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val isFavorite: Boolean = false,
    /** 1–5, where the server has ratings. */
    val rating: Int? = null,
    val playCount: Int? = null,
    val dateAdded: Instant? = null,
)

/**
 * Loudness correction in dB and peaks (1.0 = full scale), from ReplayGain tags or a server's
 * own measurement.
 */
data class Gain(
    val track: Double? = null,
    val album: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    val isEmpty: Boolean get() = track == null && album == null
}

data class Song(
    val id: String,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    /** The file's format as Subsonic names it: "mp3", "flac". */
    val suffix: String? = null,
    /** Bits per second. */
    val bitRate: Int? = null,
    val isFavorite: Boolean = false,
    val rating: Int? = null,
    val playCount: Int? = null,
    val gain: Gain? = null,
    /**
     * This entry's own id within a playlist, where the server has one (own.audio, Jellyfin).
     * Removing goes by it, since a song can be in a playlist twice. Not composite: it only means
     * something next to its playlist.
     */
    val playlistEntryId: String? = null,
    /** Whose it is and who hears it, where the server keeps a household (own.audio); null elsewhere. */
    val sharing: Sharing? = null,
    /**
     * Chapters in its file: a concert recorded as one song, a chapter per song of the show. Null
     * where the server has no chapters.
     */
    val chapterCount: Int? = null,
) {
    val isConcert: Boolean get() = (chapterCount ?: 0) > 0
}

/** Where a song of a concert begins, in a concert recorded as one file. */
data class SongChapter(val title: String, val startMs: Long)

/**
 * Who a song or playlist is for, on a server with a household. "Private" only means not shared:
 * nothing is locked, and the app shows no lock for it.
 */
enum class Sharing {
    /** The listener's own, kept to themselves. */
    MINE,

    /** The listener's own, shared with the household. */
    SHARED,

    /** Someone else's in the household, shared with everyone. */
    HOUSEHOLD;

    val isMine: Boolean get() = this != HOUSEHOLD
}

data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    /** The owner's username where the server says; edits are offered on one's own. */
    val owner: String? = null,
    /**
     * What to call the owner where [owner] is not a name (own.audio gives an id); empty when the
     * name is not known, which shows no owner.
     */
    val ownerName: String? = null,
    /** Whether the signed-in account owns it, where the server tells (own.audio); null elsewhere. */
    val isMine: Boolean? = null,
    /** Shared with the household, where the server keeps one (own.audio); null elsewhere. */
    val isShared: Boolean? = null,
    val songCount: Int = 0,
    /** Some servers always send 0. */
    val durationMs: Long? = null,
    val coverArt: String? = null,
)

/** A place in a playlist: Subsonic removes by position, own.audio and Jellyfin by the entry's own id. */
data class PlaylistEntry(val index: Int, val entryId: String?)

data class Genre(
    /** What the server filters by: the name on Subsonic, an id on own.audio and Jellyfin. */
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val songCount: Int? = null,
)

/** One version of a song's lyrics: a language, synced or plain. */
data class Lyrics(
    val lines: List<Line>,
    val isSynced: Boolean,
    /** ISO 639 code where the server says; "xxx" means unknown. */
    val language: String? = null,
    val offsetMs: Long = 0,
) {
    data class Line(val startMs: Long?, val text: String)

    /** The line to highlight at [positionMs], for synced lyrics. */
    fun lineIndex(positionMs: Long): Int? {
        if (!isSynced) return null
        val ms = positionMs - offsetMs
        return lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= ms }.takeIf { it >= 0 }
    }
}

data class SearchResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

data class FavoritesResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** A music library on a server that has several (Jellyfin). */
data class MusicLibraryInfo(val id: String, val name: String)
