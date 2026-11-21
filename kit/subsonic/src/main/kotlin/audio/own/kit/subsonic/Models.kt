package audio.own.kit.subsonic

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** `ArtistID3`. Servers send many more fields than these; only what the app shows is decoded. */
@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    /** A Navidrome extension, so optional: other servers don't send it. */
    val artistImageUrl: String? = null,
    /** When it was starred (an ISO 8601 timestamp), absent if it isn't. */
    val starred: String? = null,
) {
    companion object {
        /**
         * Navidrome signs artist images at `size=600`, about 50 KB for a tile that is at most 160
         * dp wide. Measured on a real server: 5.9 KB at 160, 15.8 KB at 300. `size` sits outside
         * the signature, so it can be rewritten safely.
         */
        fun displaySizedImageUrl(raw: String?, size: Int = 320): String? {
            val url = raw?.toHttpUrlOrNull() ?: return raw
            if (url.queryParameter("size") == null) return raw
            return url.newBuilder().setQueryParameter("size", size.toString()).build().toString()
        }
    }
}

/** `AlbumID3`. */
@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int = 0,
    /** Seconds. */
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val starred: String? = null,
    /** 1–5, absent if unrated. */
    val userRating: Int? = null,
    val playCount: Int? = null,
)

/**
 * `Child`, as used for songs. Numbers that aren't set are left out of the JSON rather than sent
 * as null, hence the nullable fields.
 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    /** Seconds. */
    val duration: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    /** Kilobits per second. */
    val bitRate: Int? = null,
    val starred: String? = null,
    /** 1–5, absent if unrated. */
    val userRating: Int? = null,
    val playCount: Int? = null,
    /** OpenSubsonic; absent on classic servers and on files without the tags. */
    val replayGain: ReplayGain? = null,
)

/** Loudness normalization values, in dB and as linear peaks (1.0 = full scale). */
@Serializable
data class ReplayGain(
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
) {
    /** Servers send an empty object for files without the tags. */
    val isEmpty: Boolean get() = trackGain == null && albumGain == null
}

/** One set of lyrics. A server may return several (languages, synced and plain). */
data class Lyrics(
    val lines: List<Line>,
    val isSynced: Boolean,
    /** ISO 639 code; "xxx" or null when unknown. */
    val language: String? = null,
    /** Added to every start time, in milliseconds. */
    val offsetMs: Int = 0,
) {
    data class Line(
        /** Milliseconds from the start, for synced lyrics. */
        val startMs: Int?,
        val text: String,
    )

    /** The line playing at [seconds], for synced lyrics. */
    fun lineIndex(seconds: Double): Int? {
        if (!isSynced) return null
        val ms = (seconds * 1000).toInt() - offsetMs
        val index = lines.indexOfLast { (it.startMs ?: Int.MAX_VALUE) <= ms }
        return index.takeIf { it >= 0 }
    }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    /**
     * The owner's username per the spec. Not every server follows it, so a mismatch should only
     * hide edit actions, never fail.
     */
    val owner: String? = null,
    val songCount: Int = 0,
    /** Seconds. Some servers always send 0; don't show it. */
    val duration: Int? = null,
    val coverArt: String? = null,
)

@Serializable
data class Genre(
    /** The genre's name; Subsonic calls it `value`. */
    val value: String,
    val albumCount: Int? = null,
    val songCount: Int? = null,
) {
    val id: String get() = value
}

data class SearchResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** Everything the user has starred (`getStarred2`). */
data class StarredResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/** What a server says about itself in every answer (OpenSubsonic); classic servers send none of it. */
@Serializable
data class ServerIdentity(
    /** e.g. "navidrome", "gonic", "ampache"; lower case per the spec. */
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
)

/** Three states and one more, so a mistyped password isn't reported as an outage. */
enum class ConnectionStatus {
    Ok,

    /** Wrong username or password. */
    Rejected,

    /** The server refuses token sign-in (Ampache, LDAP accounts), which is the only one we send. */
    Unsupported,
    Unreachable,
}

/** The `getAlbumList2` orders that need no extra parameter; by genre and by year have their own calls. */
enum class AlbumListType(val wireName: String) {
    AlphabeticalByArtist("alphabeticalByArtist"),
    AlphabeticalByName("alphabeticalByName"),
    Newest("newest"),
    Recent("recent"),
    Frequent("frequent"),
    Random("random"),
    Starred("starred"),
    Highest("highest"),
}

/** Well-known OpenSubsonic extension names. */
object OpenSubsonicExtension {
    const val SONG_LYRICS = "songLyrics"
    const val TRANSCODE_OFFSET = "transcodeOffset"
    const val FORM_POST = "formPost"
}

// Response payloads. Each endpoint decodes its own key from the same object the status is read from.

@Serializable
internal class Status(val status: String = "")

@Serializable
internal class Failure(val error: Error? = null) {
    @Serializable
    class Error(val code: Int = 0, val message: String? = null)
}

@Serializable
internal class ArtistsPayload(val artists: Artists) {
    @Serializable
    class Artists(val index: List<Index>? = null)

    @Serializable
    class Index(val artist: List<Artist>? = null)

    val flattened: List<Artist> get() = artists.index.orEmpty().flatMap { it.artist.orEmpty() }
}

@Serializable
internal class ArtistPayload(val artist: Detail) {
    @Serializable
    class Detail(
        val id: String,
        val name: String,
        val album: List<Album>? = null,
        val artistImageUrl: String? = null,
        val starred: String? = null,
    )
}

@Serializable
internal class AlbumListPayload(val albumList2: AlbumList2) {
    @Serializable
    class AlbumList2(val album: List<Album>? = null)
}

@Serializable
internal class AlbumPayload(val album: Detail) {
    @Serializable
    class Detail(
        val id: String,
        val name: String,
        val artist: String? = null,
        val artistId: String? = null,
        val duration: Int? = null,
        val coverArt: String? = null,
        val year: Int? = null,
        val starred: String? = null,
        val userRating: Int? = null,
        val playCount: Int? = null,
        val song: List<Song>? = null,
    )
}

@Serializable
internal class SongPayload(val song: Song)

@Serializable
internal class SearchPayload(val searchResult3: Result) {
    @Serializable
    class Result(
        val artist: List<Artist>? = null,
        val album: List<Album>? = null,
        val song: List<Song>? = null,
    )
}

@Serializable
internal class PlaylistsPayload(val playlists: Playlists) {
    @Serializable
    class Playlists(val playlist: List<Playlist>? = null)
}

@Serializable
internal class PlaylistPayload(val playlist: Detail) {
    @Serializable
    class Detail(
        val id: String,
        val name: String,
        val comment: String? = null,
        val owner: String? = null,
        val songCount: Int = 0,
        val duration: Int? = null,
        val coverArt: String? = null,
        val entry: List<Song>? = null,
    )
}

@Serializable
internal class CreatePlaylistPayload(val playlist: Playlist)

@Serializable
internal class StarredPayload(val starred2: Starred) {
    @Serializable
    class Starred(
        val artist: List<Artist>? = null,
        val album: List<Album>? = null,
        val song: List<Song>? = null,
    )
}

@Serializable
internal class ExtensionsPayload(val openSubsonicExtensions: List<Extension>? = null) {
    @Serializable
    class Extension(val name: String, val versions: List<Int> = emptyList())
}

@Serializable
internal class StructuredLyricsPayload(val lyricsList: LyricsList) {
    @Serializable
    class LyricsList(val structuredLyrics: List<Structured>? = null)

    @Serializable
    class Structured(
        val lang: String? = null,
        val synced: Boolean? = null,
        val offset: Int? = null,
        val line: List<Line>? = null,
    )

    @Serializable
    class Line(val start: Int? = null, val value: String = "")
}

@Serializable
internal class ClassicLyricsPayload(val lyrics: Classic? = null) {
    @Serializable
    class Classic(val value: String? = null)
}

@Serializable
internal class GenresPayload(val genres: Genres) {
    @Serializable
    class Genres(val genre: List<Genre>? = null)
}

@Serializable
internal class RandomSongsPayload(val randomSongs: Songs) {
    @Serializable
    class Songs(val song: List<Song>? = null)
}

@Serializable
internal class SongsByGenrePayload(val songsByGenre: Songs) {
    @Serializable
    class Songs(val song: List<Song>? = null)
}
