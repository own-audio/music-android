package audio.own.music.library.ownaudio

import audio.own.core.servers.CompositeId
import audio.own.kit.ownaudio.Credit
import audio.own.kit.ownaudio.Included
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import audio.own.kit.ownaudio.Album as ApiAlbum
import audio.own.kit.ownaudio.Contributor as ApiContributor
import audio.own.kit.ownaudio.Genre as ApiGenre
import audio.own.kit.ownaudio.Playlist as ApiPlaylist
import audio.own.kit.ownaudio.Track as ApiTrack

/**
 * own.audio's records (ids and credits, no names) as the app's models (names in place, ids
 * composite), with the listener's favourites and ratings, which the records don't carry.
 *
 * Albums are releases: two editions of one album are two albums, as the server keeps them.
 */
class RecordMapper(
    val serverId: String,
    contributors: Map<String, ApiContributor> = emptyMap(),
    albums: Map<String, ApiAlbum> = emptyMap(),
    genres: Map<String, ApiGenre> = emptyMap(),
    val listener: ListenerState.Values = ListenerState.Values(),
) {
    val contributors = contributors.toMutableMap()
    val albums = albums.toMutableMap()
    val genres = genres.toMutableMap()

    /** From an answer's `included` records. */
    constructor(serverId: String, included: Included?, listener: ListenerState.Values) : this(
        serverId,
        included?.contributorsById.orEmpty(),
        included?.albumsById.orEmpty(),
        included?.genresById.orEmpty(),
        listener,
    )

    private fun id(raw: String) = CompositeId.make(serverId, raw)

    fun song(t: ApiTrack, entryId: String? = null): Song {
        val album = t.albumId?.let { albums[it] }
        return Song(
            id = id(t.id),
            title = t.title,
            album = album?.title,
            artist = creditLine(t.credits, TRACK_ROLES) { contributors[it]?.name },
            albumId = t.albumId?.let(::id),
            artistId = firstArtist(t.credits)?.let(::id),
            track = t.trackNumber,
            discNumber = t.discNumber,
            durationMs = t.durationMs,
            genre = t.genreIds.firstOrNull()?.let { genres[it]?.name },
            coverArt = trackCoverId(serverId, t.id, t.image, t.albumId, album?.image),
            suffix = suffix(t.contentType),
            bitRate = bitRate(t.sizeBytes, t.durationMs),
            isFavorite = t.id in listener.favourites,
            rating = listener.ratings[t.id],
            playlistEntryId = entryId,
            sharing = sharing(t.ownerId, t.visibility, listener.userId),
            chapterCount = t.chapterCount.toInt(),
        )
    }

    fun album(a: ApiAlbum, playCount: Int? = null): Album = Album(
        id = id(a.id),
        name = a.title,
        artist = creditLine(a.credits, ALBUM_ROLES) { contributors[it]?.name },
        artistId = firstArtist(a.credits)?.let(::id),
        songCount = a.trackCount.toInt(),
        durationMs = a.durationMs,
        coverArt = coverId(serverId, a.id, a.image),
        year = year(a.releaseDate),
        isFavorite = a.id in listener.favourites,
        rating = listener.ratings[a.id],
        playCount = playCount,
        dateAdded = a.createdAt,
    )

    /** The picture by id, like a cover: its signed link runs out, the id doesn't. */
    fun artist(c: ApiContributor, albumCount: Int? = null): Artist = Artist(
        id = id(c.id),
        name = c.name,
        albumCount = albumCount,
        coverArt = coverId(serverId, c.id, c.image),
        isFavorite = c.id in listener.favourites,
    )

    /**
     * Its owner's username for the account's own (the app offers edits on those), the owner's id
     * for anyone else's, shown by the household's name for them.
     */
    fun playlist(p: ApiPlaylist, username: String, names: Map<String, String> = emptyMap()): Playlist {
        val mine = listener.userId?.let { it == p.ownerId }
        return Playlist(
            id = id(p.id),
            name = p.name,
            comment = p.description,
            owner = if (mine == true) username else p.ownerId,
            ownerName = names[p.ownerId] ?: "",
            isMine = mine,
            isShared = p.visibility == "family",
            songCount = p.trackCount.toInt(),
            durationMs = p.durationMs,
            coverArt = coverId(serverId, p.id, p.image),
        )
    }

    companion object {
        /** A track's line names its artists and featured guests; an album's only its artists. */
        val TRACK_ROLES = setOf("artist", "featured")
        val ALBUM_ROLES = setOf("artist")

        /**
         * The credit line as printed: each name (as credited on this release, where it differs)
         * followed by what joins it to the next. Null when no credit has one of [roles].
         */
        fun creditLine(credits: List<Credit>, roles: Set<String>, name: (String) -> String?): String? =
            line(credits.map { CreditPart(it.contributorId, it.role, it.joinPhrase, it.creditedAs) }, roles, name)

        fun line(credits: List<CreditPart>, roles: Set<String>, name: (String) -> String?): String? {
            val parts = credits.filter { it.role in roles }
            if (parts.isEmpty()) return null
            // A join phrase joins a name to the next one, so the last name's is dropped: an
            // album line that leaves out the featured guest would otherwise end in " feat. ".
            return parts.withIndex().joinToString("") { (i, part) ->
                (part.creditedAs?.takeIf(String::isNotEmpty) ?: name(part.contributorId).orEmpty()) +
                    if (i < parts.lastIndex) part.joinPhrase else ""
            }.ifEmpty { null }
        }

        fun firstArtist(credits: List<Credit>): String? = credits.firstOrNull { it.role == "artist" }?.contributorId

        /**
         * A cover id that changes when the image does: "<server>|<record>~<image object>". Covers
         * are cached by it, so a cover replaced on the server is fetched again, not shown stale.
         * Null when the record has no picture.
         */
        fun coverId(serverId: String, recordId: String, image: String?): String? {
            if (image == null) return null
            val url = image.toHttpUrlOrNull()
            val segments = url?.pathSegments.orEmpty()
            val at = segments.indexOf("images")
            val record = CompositeId.make(serverId, recordId)
            if (at < 1 || segments[at - 1] != "v2" || at + 1 >= segments.size) return record
            return "$record~${segments[at + 1]}"
        }

        /** A track's own picture, else its album's. */
        fun trackCoverId(serverId: String, trackId: String, image: String?, albumId: String?, albumImage: String?): String? =
            coverId(serverId, trackId, image) ?: albumId?.let { coverId(serverId, it, albumImage) }

        /** The record a cover id is for: (server id, record id). */
        fun recordOfCover(coverId: String): Pair<String?, String> {
            val bare = coverId.substringBefore('~')
            return CompositeId.split(bare)?.let { it.first to it.second } ?: (null to bare)
        }

        /**
         * Whose a track is. Unknown (null) until the account's id is: the first sync learns it.
         * Private means only "not shared": nothing is locked.
         */
        fun sharing(ownerId: String, visibility: String, me: String?): Sharing? = when {
            me == null -> null
            ownerId != me -> Sharing.HOUSEHOLD
            visibility == "family" -> Sharing.SHARED
            else -> Sharing.MINE
        }

        fun year(releaseDate: String?): Int? = releaseDate?.take(4)?.toIntOrNull()

        fun bitRate(sizeBytes: Long?, durationMs: Long?): Int? {
            val size = sizeBytes ?: return null
            val seconds = (durationMs ?: return null) / 1000
            if (size <= 0 || seconds <= 0) return null
            return (size * 8 / seconds).toInt()
        }

        /** The format from the content type, as Subsonic names it. */
        fun suffix(contentType: String?): String? = when (contentType?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "audio/vorbis" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            else -> null
        }
    }
}

/** One name of a credit line, from a record or from the catalogue. */
data class CreditPart(val contributorId: String, val role: String, val joinPhrase: String, val creditedAs: String?)
