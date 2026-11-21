package audio.own.music.library.ownaudio

import audio.own.core.servers.CompositeId
import audio.own.music.library.db.AlbumView
import audio.own.music.library.db.ArtistView
import audio.own.music.library.db.GenreView
import audio.own.music.library.db.PlaylistRow
import audio.own.music.library.db.PlaylistTrackView
import audio.own.music.library.db.TrackView
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import java.time.Instant

/** One own.audio server's catalogue rows as the app's models, with composite ids. */
class CatalogueModels(private val serverId: String, private val userId: String?) {
    private fun id(raw: String) = CompositeId.make(serverId, raw)

    fun song(v: TrackView, entryId: String? = null): Song = with(v.track) {
        Song(
            id = id(id),
            title = title,
            album = albumTitle,
            artist = artistLine,
            albumId = albumId?.let(::id),
            artistId = artistId?.let(::id),
            track = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            genre = genreName,
            coverArt = RecordMapper.trackCoverId(serverId, id, image, albumId, v.albumImage),
            suffix = RecordMapper.suffix(contentType),
            bitRate = RecordMapper.bitRate(sizeBytes, durationMs),
            isFavorite = v.isFavorite,
            rating = v.rating,
            playlistEntryId = entryId,
            sharing = RecordMapper.sharing(ownerId, visibility, userId),
            chapterCount = chapterCount,
        )
    }

    fun song(v: PlaylistTrackView): Song = song(TrackView(v.track, v.isFavorite, v.rating, v.albumImage), v.entryId)

    fun album(v: AlbumView, playCount: Int? = null): Album = with(v.album) {
        Album(
            id = id(id),
            name = title,
            artist = artistLine,
            artistId = artistId?.let(::id),
            songCount = trackCount,
            durationMs = durationMs,
            coverArt = RecordMapper.coverId(serverId, id, image),
            year = year,
            isFavorite = v.isFavorite,
            rating = v.rating,
            playCount = playCount,
            dateAdded = Instant.ofEpochMilli(createdAtMs),
        )
    }

    fun artist(v: ArtistView): Artist = with(v.artist) {
        Artist(
            id = id(id),
            name = name,
            albumCount = v.albumCount,
            coverArt = RecordMapper.coverId(serverId, id, image),
            isFavorite = v.isFavorite,
        )
    }

    /** Genre counts come from the catalogue; the API gives none. */
    fun genre(v: GenreView): Genre = Genre(id = id(v.id), name = v.name, albumCount = v.albumCount, songCount = v.songCount)

    fun playlist(p: PlaylistRow, username: String, names: Map<String, String>): Playlist {
        val mine = userId?.let { it == p.ownerId }
        return Playlist(
            id = id(p.id),
            name = p.name,
            comment = p.description,
            owner = if (mine == true) username else p.ownerId,
            ownerName = names[p.ownerId] ?: "",
            isMine = mine,
            isShared = p.visibility == "family",
            songCount = p.trackCount,
            durationMs = p.durationMs,
            coverArt = RecordMapper.coverId(serverId, p.id, p.image),
        )
    }
}
