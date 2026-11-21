package audio.own.musicplayer.downloads

import audio.own.music.library.model.Album
import audio.own.music.library.model.Gain
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadsSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song as the downloads index keeps it: every field the screens and the player read, so a
 * downloaded song shows and plays with no network and no catalogue. Ids stay composite.
 */
@Serializable
data class SongRecord(
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
    val suffix: String? = null,
    val bitRate: Int? = null,
    val isFavorite: Boolean = false,
    val rating: Int? = null,
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val sharing: String? = null,
    val chapterCount: Int? = null,
) {
    fun song() = Song(
        id = id, title = title, album = album, artist = artist, albumId = albumId, artistId = artistId, track = track,
        discNumber = discNumber, durationMs = durationMs, genre = genre, coverArt = coverArt, suffix = suffix, bitRate = bitRate,
        isFavorite = isFavorite, rating = rating,
        gain = Gain(trackGain, albumGain, trackPeak, albumPeak).takeUnless { it.isEmpty && trackPeak == null && albumPeak == null },
        sharing = sharing?.let { s -> Sharing.entries.firstOrNull { it.name == s } },
        chapterCount = chapterCount,
    )

    companion object {
        fun of(song: Song) = SongRecord(
            id = song.id, title = song.title, album = song.album, artist = song.artist, albumId = song.albumId, artistId = song.artistId,
            track = song.track, discNumber = song.discNumber, durationMs = song.durationMs, genre = song.genre, coverArt = song.coverArt,
            suffix = song.suffix, bitRate = song.bitRate, isFavorite = song.isFavorite, rating = song.rating,
            trackGain = song.gain?.track, albumGain = song.gain?.album, trackPeak = song.gain?.trackPeak, albumPeak = song.gain?.albumPeak,
            sharing = song.sharing?.name, chapterCount = song.chapterCount,
        )
    }
}

/** An album or a playlist downloaded as a whole. */
@Serializable
data class CollectionRecord(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
) {
    fun album(songCount: Int, durationMs: Long?) = Album(
        id = id, name = name, artist = artist, artistId = artistId, songCount = songCount, durationMs = durationMs,
        coverArt = coverArt, year = year, genre = genre,
    )

    fun playlist(songCount: Int) = Playlist(id = id, name = name, songCount = songCount, coverArt = coverArt)

    companion object {
        fun of(album: Album) = CollectionRecord(album.id, album.name, album.artist, album.artistId, album.year, album.genre, album.coverArt ?: album.id)
        fun of(playlist: Playlist) = CollectionRecord(playlist.id, playlist.name, coverArt = playlist.coverArt)
    }
}

/** The song a download's record holds; null for a record this version can't read. */
fun DownloadEntity.song(): Song? = runCatching { recordJson.decodeFromString(SongRecord.serializer(), record).song() }.getOrNull()

fun DownloadsSnapshot.CollectionView.record(): CollectionRecord? =
    collection.record?.let { runCatching { recordJson.decodeFromString(CollectionRecord.serializer(), it) }.getOrNull() }

/** The downloaded songs, of one server or all. */
fun DownloadsSnapshot.songs(serverId: String? = null): List<DownloadedSong> =
    done.filter { serverId == null || it.serverId == serverId }.mapNotNull { e -> e.song()?.let { DownloadedSong(e, it) } }

/** A collection's songs that are on the phone, in its order. */
fun DownloadsSnapshot.songs(view: DownloadsSnapshot.CollectionView): List<DownloadedSong> =
    view.keys.mapNotNull { key -> items[key]?.takeIf { it.state == DownloadEntity.DONE }?.let { e -> e.song()?.let { DownloadedSong(e, it) } } }

internal val recordJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
