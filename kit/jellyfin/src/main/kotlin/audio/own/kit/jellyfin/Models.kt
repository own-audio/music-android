package audio.own.kit.jellyfin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// The app's view of a Jellyfin music library. Field names follow the Subsonic kit's models where
// the meaning is the same, so the screens built on both stay alike. Item ids are Jellyfin's; a
// cover is named by the id of the item that holds the image.

data class Artist(
    val id: String,
    val name: String,
    /** Not every listing sends it. */
    val albumCount: Int? = null,
    /** Set when the artist has its own picture. */
    val imageId: String? = null,
    val isFavorite: Boolean = false,
)

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
    val genre: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int? = null,
    val dateAdded: Instant? = null,
)

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
    /** The file's container, e.g. "mp3", "flac". */
    val suffix: String? = null,
    /** Bits per second. */
    val bitRate: Int? = null,
    val isFavorite: Boolean = false,
    val playCount: Int? = null,
    /** The loudness correction the server measured, in dB. Absent until the library's "normalization" option has run. */
    val normalizationGain: Double? = null,
    val hasLyrics: Boolean = false,
    /** This entry's id within a playlist; removing from a playlist goes by it, since the same song can be in a playlist twice. */
    val playlistItemId: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    /** Seconds. */
    val duration: Int? = null,
    val coverArt: String? = null,
)

data class Genre(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val songCount: Int? = null,
)

/** A music library on the server. A server can have several. */
data class JellyfinLibrary(val id: String, val name: String)

data class SearchResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

data class FavoritesResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
)

data class Lyrics(val lines: List<Line>, val isSynced: Boolean) {
    data class Line(val startMs: Int?, val text: String)

    /** The line to highlight at [seconds], for synced lyrics. */
    fun lineIndex(seconds: Double): Int? {
        if (!isSynced) return null
        val ms = (seconds * 1000).toInt()
        return lines.indexOfLast { (it.startMs ?: Int.MAX_VALUE) <= ms }.takeIf { it >= 0 }
    }
}

/** What `System/Info/Public` says, before signing in. */
@Serializable
data class ServerInfo(
    @SerialName("Id") val id: String,
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
    /**
     * The address the server sees itself at on its own network, e.g. `http://192.168.1.20:8096`.
     * Behind Docker it can be a container address nobody else reaches, so it is a candidate to
     * try, not a fact.
     */
    @SerialName("LocalAddress") val localAddress: String? = null,
)

/** A successful sign-in. */
@Serializable
data class Session(
    val userId: String,
    val userName: String,
    val accessToken: String,
    val serverId: String,
)

/** Quick Connect: the app shows [code], the listener approves it in another signed-in Jellyfin app, and this device gets a session. */
data class QuickConnect(val secret: String, val code: String, val isApproved: Boolean)

/** Album orders the server can list in. */
enum class AlbumSort { Name, Artist, Newest, RecentlyPlayed, MostPlayed, Random, Favorites }

enum class ConnectionStatus { Ok, Rejected, Unreachable }

// Wire format

/**
 * Jellyfin writes dates with seven fractional digits ("2025-02-22T21:32:03.0000000Z") and
 * sometimes without a zone; both are read, as UTC when there is none. An unreadable date becomes
 * null rather than failing the whole page.
 */
internal object JellyfinDateSerializer : KSerializer<Instant?> {
    override val descriptor = PrimitiveSerialDescriptor("JellyfinDate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant? = parseDate(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant?) {
        encoder.encodeString(value.toString())
    }

    fun parseDate(raw: String): Instant? = try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        try {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** `BaseItemDto`, cut down to what a music player reads. */
@Serializable
internal class ItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameId>? = null,
    @SerialName("ArtistItems") val artistItems: List<NameId>? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("ImageTags") val imageTags: ImageTags? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("AlbumCount") val albumCount: Int? = null,
    @SerialName("SongCount") val songCount: Int? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource>? = null,
    @SerialName("NormalizationGain") val normalizationGain: Double? = null,
    @SerialName("HasLyrics") val hasLyrics: Boolean? = null,
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
    @Serializable(with = JellyfinDateSerializer::class)
    @SerialName("DateCreated") val dateCreated: Instant? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
) {
    @Serializable
    class NameId(@SerialName("Name") val name: String? = null, @SerialName("Id") val id: String? = null)

    @Serializable
    class UserData(@SerialName("IsFavorite") val isFavorite: Boolean? = null, @SerialName("PlayCount") val playCount: Int? = null)

    @Serializable
    class ImageTags(@SerialName("Primary") val primary: String? = null)

    @Serializable
    class MediaSource(@SerialName("Container") val container: String? = null, @SerialName("Bitrate") val bitrate: Int? = null)

    /** Libraries with sloppy tags send names made of spaces; those count as no name. */
    private val trimmedName: String get() = name.orEmpty().trim()

    /** 100-nanosecond ticks to whole seconds. */
    private val seconds: Int? get() = runTimeTicks?.let { ((it + 5_000_000) / 10_000_000).toInt() }

    fun asArtist() = Artist(
        id = id, name = trimmedName, albumCount = albumCount ?: childCount,
        imageId = if (imageTags?.primary != null) id else null, isFavorite = userData?.isFavorite ?: false,
    )

    fun asAlbum(): Album {
        val artist = albumArtists?.firstOrNull() ?: artistItems?.firstOrNull()
        return Album(
            id = id, name = trimmedName, artist = albumArtist ?: artist?.name, artistId = artist?.id,
            songCount = childCount ?: songCount ?: 0, duration = seconds,
            coverArt = if (imageTags?.primary != null) id else null, year = productionYear,
            genre = genres?.firstOrNull(), isFavorite = userData?.isFavorite ?: false,
            playCount = userData?.playCount, dateAdded = dateCreated,
        )
    }

    fun asSong(): Song {
        val source = mediaSources?.firstOrNull()
        val cover = when {
            imageTags?.primary != null -> id
            albumPrimaryImageTag != null -> albumId
            else -> null
        }
        return Song(
            id = id, title = trimmedName, album = album, artist = artistItems?.firstOrNull()?.name ?: albumArtist,
            albumId = albumId, artistId = artistItems?.firstOrNull()?.id ?: albumArtists?.firstOrNull()?.id,
            track = indexNumber,
            // The disc number is 0 on files without a disc tag; that isn't a real disc.
            discNumber = parentIndexNumber?.takeIf { it > 0 },
            duration = seconds, genre = genres?.firstOrNull(), coverArt = cover,
            suffix = container ?: source?.container, bitRate = source?.bitrate,
            isFavorite = userData?.isFavorite ?: false, playCount = userData?.playCount,
            normalizationGain = normalizationGain, hasLyrics = hasLyrics ?: false, playlistItemId = playlistItemId,
        )
    }

    fun asPlaylist() = Playlist(
        id = id, name = trimmedName, songCount = childCount ?: 0, duration = seconds,
        coverArt = if (imageTags?.primary != null) id else null,
    )

    fun asGenre() = Genre(id = id, name = name.orEmpty(), albumCount = albumCount, songCount = songCount)
}

@Serializable
internal class ItemsResponse(
    @SerialName("Items") val items: List<ItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
internal class AuthResponse(
    @SerialName("User") val user: User,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String,
) {
    @Serializable
    class User(@SerialName("Id") val id: String, @SerialName("Name") val name: String)

    val session: Session get() = Session(user.id, user.name, accessToken, serverId)
}

@Serializable
internal class LyricsDto(@SerialName("Lyrics") val lyrics: List<Line>? = null) {
    @Serializable
    class Line(@SerialName("Text") val text: String? = null, @SerialName("Start") val start: Long? = null)
}

@Serializable
internal class QuickConnectDto(
    @SerialName("Secret") val secret: String,
    @SerialName("Code") val code: String,
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

@Serializable
internal class CreatedDto(@SerialName("Id") val id: String)
