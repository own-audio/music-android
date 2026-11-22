package audio.own.music.library.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

// The catalogue on the phone: every server's library in one database, each row keyed by the
// server record's id and the item's own id (not the composite one: 600k rows × a repeated
// server id add up). iOS kept one JSON file per server in memory; a 600k-song library does not
// fit in a phone's memory, so here it is tables and indexes.
//
// Names and the credit line are copied onto albums and tracks ("denormalized") so that sorting
// and searching 600k tracks by artist or album is an index lookup, not a join per row. The
// writer keeps the copies right when a contributor, album or genre changes.
//
// `gen` is the snapshot generation a row was last written in. A fresh snapshot writes rows with
// a new generation and only at its end removes the rows of older ones, so a snapshot that fails
// halfway leaves a usable (if partly stale) catalogue instead of an empty one.
//
// Text columns that are sorted or searched are COLLATE NOCASE, so their indexes serve
// case-insensitive ORDER BY and `=`; LIKE is case-insensitive for ASCII anyway.

@Entity(tableName = "artist", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "name")])
data class ArtistRow(
    val serverId: String,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val sortName: String?,
    /** A signed link that runs out; see CoverIds. */
    val image: String?,
    val gen: Long,
)

@Entity(
    tableName = "album",
    primaryKeys = ["serverId", "id"],
    indices = [
        Index("serverId", "title"),
        Index("serverId", "artistLine", "year", "title"),
        Index("serverId", "createdAtMs"),
        Index("serverId", "artistId"),
    ],
)
data class AlbumRow(
    val serverId: String,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    /** The credit line as printed ("A & B"); null when the album credits no artist. */
    @ColumnInfo(collate = ColumnInfo.NOCASE) val artistLine: String?,
    /** The first credited artist, for "go to artist". */
    val artistId: String?,
    val year: Int?,
    val releaseDate: String?,
    val releaseGroupId: String?,
    val isCompilation: Boolean,
    val trackCount: Int,
    val durationMs: Long,
    val discCount: Int,
    val image: String?,
    val createdAtMs: Long,
    val gen: Long,
)

@Entity(
    tableName = "track",
    primaryKeys = ["serverId", "id"],
    indices = [
        Index("serverId", "albumId", "discNumber", "trackNumber"),
        // Sorting by title, and the search's scan: the searched columns ride along, so a
        // search reads this index instead of every whole row.
        Index("serverId", "title", "artistLine", "albumTitle"),
    ],
)
data class TrackRow(
    val serverId: String,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    val albumId: String?,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val albumTitle: String?,
    /** Artists and featured artists, as printed ("A feat. B"). */
    @ColumnInfo(collate = ColumnInfo.NOCASE) val artistLine: String?,
    val artistId: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    /** The first genre's name; every genre is in track_genre. */
    val genreName: String?,
    val contentType: String?,
    val sizeBytes: Long?,
    val image: String?,
    val ownerId: String,
    /** `private` or `family`. */
    val visibility: String,
    val chapterCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val gen: Long,
    // What Subsonic and Jellyfin say about the file and its loudness (version 2). own.audio's
    // records carry a content type and size instead, and no gain.
    /** The file's format as Subsonic names it: "mp3", "flac". */
    val suffix: String? = null,
    /** Bits per second. */
    val bitRate: Int? = null,
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)

@Entity(tableName = "genre", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "name")])
data class GenreRow(
    val serverId: String,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val gen: Long,
)

/**
 * A track's genres in order ([ord] 0 is the one shown); v2 gives a track several. The track's
 * album is copied here so a genre's album count and albums come from this table's index alone.
 */
@Entity(
    tableName = "track_genre",
    primaryKeys = ["serverId", "trackId", "genreId"],
    indices = [Index("serverId", "genreId", "albumId")],
)
data class TrackGenreRow(val serverId: String, val trackId: String, val genreId: String, val ord: Int, val albumId: String?, val gen: Long)

/** One name of an album's credit line, in order ([ord]). */
@Entity(
    tableName = "album_credit",
    primaryKeys = ["serverId", "albumId", "ord"],
    indices = [Index("serverId", "contributorId", "role")],
)
data class AlbumCreditRow(
    val serverId: String,
    val albumId: String,
    val ord: Int,
    val contributorId: String,
    val role: String,
    /** What follows this name in the line: " & ", " feat. ", or nothing. */
    val joinPhrase: String,
    /** The name as printed on this release, where it differs from the contributor's. */
    val creditedAs: String?,
    val gen: Long,
)

@Entity(
    tableName = "track_credit",
    primaryKeys = ["serverId", "trackId", "ord"],
    indices = [Index("serverId", "contributorId", "role")],
)
data class TrackCreditRow(
    val serverId: String,
    val trackId: String,
    val ord: Int,
    val contributorId: String,
    val role: String,
    val joinPhrase: String,
    val creditedAs: String?,
    val gen: Long,
)

@Entity(tableName = "playlist", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "name")])
data class PlaylistRow(
    val serverId: String,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val description: String?,
    val visibility: String,
    val ownerId: String,
    val trackCount: Int,
    val durationMs: Long,
    val image: String?,
    val generated: Boolean,
    val updatedAtMs: Long,
    val gen: Long,
)

/**
 * A playlist's entries as last read from the server. The feed sends playlists without their
 * entries, so these are written when a playlist is opened online, for opening it offline.
 */
@Entity(
    tableName = "playlist_entry",
    primaryKeys = ["serverId", "playlistId", "entryId"],
    indices = [Index("serverId", "playlistId", "position")],
)
data class PlaylistEntryRow(
    val serverId: String,
    val playlistId: String,
    val entryId: String,
    val position: Int,
    val trackId: String,
)

/** A favourite track, album or contributor ([kind] as the API names it). */
@Entity(tableName = "favourite", primaryKeys = ["serverId", "kind", "id"])
data class FavouriteRow(val serverId: String, val kind: String, val id: String, val createdAtMs: Long, val gen: Long)

@Entity(tableName = "rating", primaryKeys = ["serverId", "kind", "id"])
data class RatingRow(val serverId: String, val kind: String, val id: String, val rating: Int, val updatedAtMs: Long, val gen: Long)

/**
 * Where a server's change feed was read up to. [cursor] is null until the first snapshot has
 * finished; while one runs, [snapshotCursor] and [snapshotAfter] say where to resume it, even
 * after the app was killed.
 */
@Entity(tableName = "feed_state", primaryKeys = ["serverId"])
data class FeedStateRow(
    val serverId: String,
    val cursor: String?,
    val syncedAtMs: Long?,
    /** The signed-in account, once known: whose songs and playlists are whose. */
    val userId: String?,
    /** The generation the current (or last finished) snapshot writes. */
    val generation: Long,
    val snapshotCursor: String?,
    val snapshotAfter: String?,
)

// Rows with the listener's marks joined on, as the browsing queries return them.

data class TrackView(
    @Embedded val track: TrackRow,
    val isFavorite: Boolean,
    val rating: Int?,
    /** A track without a picture of its own shows its album's. */
    val albumImage: String?,
)

data class AlbumView(
    @Embedded val album: AlbumRow,
    val isFavorite: Boolean,
    val rating: Int?,
)

data class ArtistView(
    @Embedded val artist: ArtistRow,
    val albumCount: Int,
    val isFavorite: Boolean,
)

data class GenreView(
    val id: String,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
)

/** A credit with the contributor's name, for rebuilding a credit line. */
data class NamedCredit(
    val ord: Int,
    val contributorId: String,
    val role: String,
    val joinPhrase: String,
    val creditedAs: String?,
    val name: String?,
)
