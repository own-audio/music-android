package audio.own.music.library

import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.MusicLibraryInfo
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import java.time.Instant

/**
 * What a server can do beyond what every source has (browse, search, play, favourites,
 * playlists). Screens ask before offering a feature, so a missing one is hidden, not broken.
 */
enum class Capability {
    /** Ratings from 1 to 5. */
    RATINGS,

    /** Songs like a song, album or artist, picked by the server. */
    INSTANT_MIX,

    /** The server makes a smaller stream on request. */
    LOWER_BITRATE,

    /** The server holds several music libraries and listings can be limited to one. */
    LIBRARY_CHOICE,

    /**
     * The server counts plays itself from the playback reports it was sent while playing; only
     * plays it never heard about (made offline) are reported afterwards.
     */
    COUNTS_PLAYS_FROM_REPORTS,
}

/** Album orders a source can list in. [HIGHEST_RATED] needs [Capability.RATINGS]. */
enum class AlbumSort { NAME, ARTIST, NEWEST, RECENTLY_PLAYED, MOST_PLAYED, RANDOM, FAVORITES, HIGHEST_RATED }

enum class FavoriteKind { SONG, ALBUM, ARTIST }

enum class ConnectionStatus {
    OK,

    /** Reachable, but the sign-in is refused. */
    REJECTED,
    UNREACHABLE,
}

/** The errors screens show, whatever the server. */
sealed class SourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SignInRejected(cause: Throwable? = null) : SourceError("sign-in rejected", cause)
    class NotAllowed(cause: Throwable? = null) : SourceError("not allowed", cause)
    class NotFound(cause: Throwable? = null) : SourceError("not found", cause)
    class Http(val status: Int) : SourceError("HTTP $status")

    /** The answer couldn't be read. */
    class Unreadable(cause: Throwable? = null) : SourceError("unreadable answer", cause)
    class Network(cause: Throwable? = null) : SourceError("network", cause)

    /** Offline, and this wasn't saved from an earlier visit. */
    class Offline : SourceError("offline")

    /** The server doesn't have this feature. */
    class Unsupported : SourceError("unsupported")

    /** A server that charges found the household's credit short (`402 payment_required`). */
    class PaymentRequired(cause: Throwable? = null) : SourceError("payment required", cause)

    /** The item isn't in a state the request needs (`409`), such as undoing what was never done. */
    class Conflict(val detail: String?, cause: Throwable? = null) : SourceError(detail ?: "conflict", cause)

    /** The server's own message. */
    class Server(val text: String) : SourceError(text)
}

data class AlbumWithSongs(val album: Album, val songs: List<Song>)

data class PlaylistWithSongs(val playlist: Playlist, val songs: List<Song>)

/**
 * Where to fetch a song's audio. own.audio signs a link per song that runs out, so the player
 * asks for one when a song starts and again when [expiresAt] is near; servers that build links on
 * the phone leave it null.
 */
data class PlaybackLink(
    val url: String,
    val expiresAt: Instant? = null,
    /** What the link answers with, where the server says. */
    val contentType: String? = null,
    val bitrateKbps: Int? = null,
    /** The server makes a new stream rather than sending the file. */
    val isTranscoded: Boolean = false,
)

/**
 * One server, whatever its kind. Every id passed in and handed out is composite
 * ("<server id>|<item id>"). Every call may throw [SourceError].
 */
interface MusicSource {
    /** The server record's id: the prefix of every id this source hands out. */
    val serverId: String
    val capabilities: Set<Capability>
    val albumSorts: List<AlbumSort>

    suspend fun checkConnection(): ConnectionStatus

    // Browsing
    suspend fun artists(): List<Artist>
    suspend fun albums(artistId: String): List<Album>
    suspend fun albums(sort: AlbumSort, limit: Int, offset: Int = 0): List<Album>
    suspend fun albums(genre: Genre, limit: Int): List<Album>
    suspend fun album(id: String): AlbumWithSongs
    suspend fun song(id: String): Song
    suspend fun songs(limit: Int, offset: Int = 0): List<Song>
    suspend fun randomSongs(limit: Int, genre: Genre? = null): List<Song>
    suspend fun genres(): List<Genre>
    suspend fun search(text: String): SearchResult
    suspend fun instantMix(itemId: String): List<Song>

    // Favourites and ratings
    suspend fun favorites(): FavoritesResult
    suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean)

    /** 1–5, or 0 to clear. */
    suspend fun setRating(id: String, rating: Int)

    // Playlists
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): PlaylistWithSongs
    suspend fun createPlaylist(name: String, songIds: List<String>)
    suspend fun addToPlaylist(id: String, songIds: List<String>)
    suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>)
    suspend fun renamePlaylist(id: String, name: String)
    suspend fun deletePlaylist(id: String)

    /** One entry per version (language, synced or plain); empty when there are none. */
    suspend fun lyrics(song: Song): List<Lyrics>

    // Playback reporting
    suspend fun reportNowPlaying(songId: String, positionMs: Long)
    suspend fun reportProgress(songId: String, positionMs: Long, isPaused: Boolean)
    suspend fun reportStopped(songId: String, positionMs: Long)

    /** A counted play; [at] is when listening began, for plays reported late. */
    suspend fun reportPlayed(songId: String, at: Instant)

    // Links. A server may have to be asked for them (own.audio signs one per song), so they
    // suspend; servers that build them on the phone answer at once.

    /** The audio; with [maxBitrateKbps] a smaller stream where the server makes one. */
    suspend fun playbackLink(songId: String, maxBitrateKbps: Int? = null): PlaybackLink

    /** The original file, for keeping on the phone. */
    suspend fun downloadLink(songId: String): PlaybackLink

    /** A cover by the id a model carries in `coverArt`, at the nearest size the server makes. */
    suspend fun coverLink(id: String, size: Int): String

    /** Several music libraries; empty where the server has one. */
    suspend fun musicLibraries(): List<MusicLibraryInfo>

    /** Ends the session on the server where there is one. */
    suspend fun signOut()
}
