package audio.own.musicplayer.auto

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.AlbumSort
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.downloads.song
import audio.own.musicplayer.downloads.songs
import audio.own.musicplayer.platform.VoiceSearch
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the car's screen shows, apart from the media service so tests can check it without a car:
 * the root's lists and how each is built. Ported from the iOS app's `CarPlayContent`.
 */
object AutoContent {
    /** The car's guidelines ask for short lists. */
    const val MAX_ITEMS = 100

    const val ROOT = "@root"
    const val MORE = "@more"
    const val ARTIST_PREFIX = "@artist/"
    const val ALBUM_PREFIX = "@album/"
    const val PLAYLIST_PREFIX = "@playlist/"
    const val COLLECTION_PREFIX = "@collection/"
    const val FAVORITE_SONGS = "@favorite-songs"
    const val LOOSE_SONGS = "@downloaded-songs"
    const val MESSAGE_PREFIX = "@message/"

    enum class RootEntry(val id: String) {
        RECENTLY_PLAYED("@recently-played"),
        RECENTLY_ADDED("@recently-added"),
        DOWNLOADS("@downloads"),
        PLAYLISTS("@playlists"),
        ARTISTS("@artists"),
        FAVORITES("@favorites"),
    }

    /** With no server, no lists: the root says to add one on the phone. */
    fun rootEntries(hasServer: Boolean): List<RootEntry> = if (hasServer) RootEntry.entries else emptyList()

    /**
     * The car shows the root's folders as tabs and allows only so many ([limit], 4 on most
     * cars): past that, the last tab is More with the rest in it.
     */
    fun tabs(entries: List<RootEntry>, limit: Int): Pair<List<RootEntry>, List<RootEntry>> {
        if (limit <= 0 || entries.size <= limit) return entries to emptyList()
        val shown = (limit - 1).coerceAtLeast(1)
        return entries.take(shown) to entries.drop(shown)
    }

    sealed interface DownloadRow {
        /** Downloaded songs that belong to no downloaded album or playlist, as one row. */
        data class LooseSongs(val count: Int) : DownloadRow

        data class Collection(val view: DownloadsSnapshot.CollectionView) : DownloadRow
    }

    /** Downloads: the loose songs first, then albums and playlists by name, within the cap. */
    fun downloadRows(snapshot: DownloadsSnapshot, cap: Int = MAX_ITEMS): List<DownloadRow> {
        val looseCount = snapshot.loose.size
        val loose = if (looseCount > 0) listOf(DownloadRow.LooseSongs(looseCount)) else emptyList()
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val withSongs = snapshot.collections.filter { view -> view.keys.any(snapshot::isDownloaded) }
        val sorted = withSongs.sortedWith(compareBy(collator) { it.collection.name })
        return loose + sorted.take(cap - loose.size).map { DownloadRow.Collection(it) }
    }

    sealed interface FavoriteRow {
        data class Songs(val count: Int) : FavoriteRow

        data class AlbumRow(val album: Album) : FavoriteRow
    }

    /** Favorites: all favourite songs as one row first, then the favourite albums, within the cap. */
    fun favoriteRows(songs: Int, albums: List<Album>, cap: Int = MAX_ITEMS): List<FavoriteRow> {
        val first = if (songs > 0) listOf(FavoriteRow.Songs(songs)) else emptyList()
        return first + albums.take(cap - first.size).map { FavoriteRow.AlbumRow(it) }
    }

    /** What a row plays: the songs, which to start at, and the container the queue remembers. */
    data class Playable(val songs: List<Song>, val startId: String? = null, val containerId: String? = null)
}

/**
 * The browse tree Android Auto (and any media browser) reads. Albums and playlists are rows that
 * play from their first song, as on the iPhone; an artist opens its albums. An empty list or a
 * failure is one row that says so, never an empty screen.
 */
@UnstableApi
class AutoLibrary(
    private val library: Library,
    private val words: Words,
    /** A cover's address as the car may read it (a content URI), or null. */
    private val artwork: (String?) -> Uri? = { null },
) {
    interface Library {
        val hasServer: Boolean

        /** The active server's source as the screens read it (the downloads when offline). */
        fun source(): MusicSource?

        val downloads: DownloadsSnapshot
    }

    interface Words {
        fun title(entry: AutoContent.RootEntry): String
        val more: String
        val addServer: String
        val nothingHere: String
        val noPlaylists: String
        val noArtists: String
        val noAlbums: String
        val noFavorites: String
        val nothingDownloaded: String
        val favoriteSongs: String
        val downloadedSongs: String
        val albums: String
        val songs: String
        val artists: String
        val playlists: String

        fun songCount(count: Int): String

        fun failure(error: Throwable): String
    }

    /** Songs of the lists shown (search results), so a tap on one plays that list from it. */
    private val listedSongs = ConcurrentHashMap<String, List<Song>>()

    fun root(): MediaItem = folder(AutoContent.ROOT, "", playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)

    /** How many folders the car shows at the root (`EXTRAS_KEY_ROOT_CHILDREN_LIMIT`), as it last said. */
    @Volatile var tabLimit: Int = Int.MAX_VALUE

    suspend fun children(parentId: String): List<MediaItem> {
        val entries = AutoContent.rootEntries(library.hasServer)
        val (tabs, more) = AutoContent.tabs(entries, tabLimit)
        if (parentId == AutoContent.ROOT) {
            if (entries.isEmpty()) return listOf(message(words.addServer))
            return tabs.map(::entry) + listOfNotNull(more.takeIf { it.isNotEmpty() }?.let { folder(AutoContent.MORE, words.more) })
        }
        if (parentId == AutoContent.MORE) return more.map(::entry)
        if (parentId == AutoContent.RootEntry.DOWNLOADS.id) return downloads()
        if (!library.hasServer) return listOf(message(words.addServer))
        val source = library.source() ?: return listOf(message(words.addServer))
        return guarded {
            when {
                parentId == AutoContent.RootEntry.RECENTLY_PLAYED.id ->
                    source.albums(AlbumSort.RECENTLY_PLAYED, AutoContent.MAX_ITEMS).map(::album).ifEmpty { listOf(message(words.nothingHere)) }
                parentId == AutoContent.RootEntry.RECENTLY_ADDED.id ->
                    source.albums(AlbumSort.NEWEST, AutoContent.MAX_ITEMS).map(::album).ifEmpty { listOf(message(words.nothingHere)) }
                parentId == AutoContent.RootEntry.PLAYLISTS.id ->
                    source.playlists().take(AutoContent.MAX_ITEMS).map(::playlist).ifEmpty { listOf(message(words.noPlaylists)) }
                parentId == AutoContent.RootEntry.ARTISTS.id ->
                    source.artists().take(AutoContent.MAX_ITEMS).map(::artist).ifEmpty { listOf(message(words.noArtists)) }
                parentId == AutoContent.RootEntry.FAVORITES.id -> favorites(source)
                parentId.startsWith(AutoContent.ARTIST_PREFIX) ->
                    source.albums(parentId.removePrefix(AutoContent.ARTIST_PREFIX)).take(AutoContent.MAX_ITEMS).map(::album).ifEmpty { listOf(message(words.noAlbums)) }
                else -> emptyList()
            }
        }
    }

    fun item(id: String): MediaItem? = when {
        id == AutoContent.ROOT -> root()
        id == AutoContent.MORE -> folder(AutoContent.MORE, words.more)
        else -> AutoContent.RootEntry.entries.firstOrNull { it.id == id }?.let(::entry)
    }

    /** What a search in the car finds: albums, playlists, artists and songs, at most the cap together. */
    suspend fun search(query: String): List<MediaItem> {
        val source = library.source() ?: return listOf(message(words.addServer))
        return guarded {
            val found = source.search(query)
            val playlists = VoiceSearch.rankedPlaylists(query, runCatching { source.playlists() }.getOrDefault(emptyList())).map { it.first }
            listedSongs[SEARCH] = found.songs
            val items = found.albums.map { album(it, group = words.albums) } +
                playlists.map { playlist(it, group = words.playlists) } +
                found.artists.map { artist(it, group = words.artists) } +
                found.songs.map { song(it, group = words.songs) }
            items.take(AutoContent.MAX_ITEMS).ifEmpty { listOf(message(words.nothingHere)) }
        }
    }

    /** Whether [id] is a row of this tree (else it came from elsewhere, such as the queue). */
    fun knows(id: String): Boolean = id.startsWith("@") || listedSongs.values.any { songs -> songs.any { it.id == id } }

    /** What a tapped row plays, or null when the id isn't one of the tree's. */
    suspend fun playable(id: String): AutoContent.Playable? {
        val snapshot = library.downloads
        return when {
            id == AutoContent.LOOSE_SONGS -> AutoContent.Playable(snapshot.loose.mapNotNull { it.song() })
            id.startsWith(AutoContent.COLLECTION_PREFIX) -> {
                val view = snapshot.collection(id.removePrefix(AutoContent.COLLECTION_PREFIX)) ?: return null
                AutoContent.Playable(snapshot.songs(view).map { it.song }, containerId = view.collection.id)
            }
            id.startsWith(AutoContent.ALBUM_PREFIX) -> {
                val albumId = id.removePrefix(AutoContent.ALBUM_PREFIX)
                AutoContent.Playable(library.source()?.album(albumId)?.songs.orEmpty(), containerId = "album:$albumId")
            }
            id.startsWith(AutoContent.PLAYLIST_PREFIX) -> {
                val playlistId = id.removePrefix(AutoContent.PLAYLIST_PREFIX)
                AutoContent.Playable(library.source()?.playlist(playlistId)?.songs.orEmpty(), containerId = "playlist:$playlistId")
            }
            id == AutoContent.FAVORITE_SONGS -> AutoContent.Playable(library.source()?.favorites()?.songs.orEmpty(), containerId = "favorites")
            id.startsWith("@") -> null
            else -> {
                val list = listedSongs.values.firstOrNull { songs -> songs.any { it.id == id } } ?: return null
                AutoContent.Playable(list, startId = id)
            }
        }
    }

    private fun downloads(): List<MediaItem> {
        val snapshot = library.downloads
        return AutoContent.downloadRows(snapshot).map { row ->
            when (row) {
                is AutoContent.DownloadRow.LooseSongs -> playableRow(AutoContent.LOOSE_SONGS, words.downloadedSongs, words.songCount(row.count), null, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                is AutoContent.DownloadRow.Collection -> {
                    val c = row.view.collection
                    val type = if (c.kind == DownloadsModel.ALBUM) MediaMetadata.MEDIA_TYPE_ALBUM else MediaMetadata.MEDIA_TYPE_PLAYLIST
                    playableRow("${AutoContent.COLLECTION_PREFIX}${c.id}", c.name, c.subtitle, c.coverId, type)
                }
            }
        }.ifEmpty { listOf(message(words.nothingDownloaded)) }
    }

    private suspend fun favorites(source: MusicSource): List<MediaItem> {
        val starred = source.favorites()
        return AutoContent.favoriteRows(starred.songs.size, starred.albums).map { row ->
            when (row) {
                is AutoContent.FavoriteRow.Songs -> playableRow(AutoContent.FAVORITE_SONGS, words.favoriteSongs, words.songCount(row.count), null, MediaMetadata.MEDIA_TYPE_PLAYLIST)
                is AutoContent.FavoriteRow.AlbumRow -> album(row.album)
            }
        }.ifEmpty { listOf(message(words.noFavorites)) }
    }

    private suspend fun guarded(work: suspend () -> List<MediaItem>): List<MediaItem> = try {
        work()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        listOf(message(words.failure(error)))
    }

    private fun entry(entry: AutoContent.RootEntry): MediaItem = when (entry) {
        AutoContent.RootEntry.RECENTLY_PLAYED, AutoContent.RootEntry.RECENTLY_ADDED ->
            folder(entry.id, words.title(entry), playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        AutoContent.RootEntry.ARTISTS ->
            folder(entry.id, words.title(entry), browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        else -> folder(entry.id, words.title(entry), playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    private fun folder(id: String, title: String, playable: Int? = null, browsable: Int? = null): MediaItem {
        val extras = Bundle().apply {
            playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
            browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun album(album: Album, group: String? = null): MediaItem =
        playableRow("${AutoContent.ALBUM_PREFIX}${album.id}", album.name, album.artist, album.coverArt ?: album.id, MediaMetadata.MEDIA_TYPE_ALBUM, group)

    private fun playlist(playlist: Playlist, group: String? = null): MediaItem =
        playableRow("${AutoContent.PLAYLIST_PREFIX}${playlist.id}", playlist.name, words.songCount(playlist.songCount), playlist.coverArt, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)

    private fun artist(artist: Artist, group: String? = null): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId("${AutoContent.ARTIST_PREFIX}${artist.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(artist.name)
                    .setArtworkUri(artwork(artist.coverArt))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun song(song: Song, group: String? = null): MediaItem =
        playableRow(song.id, song.title, song.artist, song.coverArt ?: song.albumId, MediaMetadata.MEDIA_TYPE_MUSIC, group, durationMs = song.durationMs)

    private fun playableRow(id: String, title: String, subtitle: String?, coverId: String?, type: Int, group: String? = null, durationMs: Long? = null): MediaItem {
        val extras = group?.let { Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(artwork(coverId))
                    .setDurationMs(durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun message(text: String): MediaItem = MediaItem.Builder()
        .setMediaId("${AutoContent.MESSAGE_PREFIX}${text.hashCode()}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(text).setIsBrowsable(false).setIsPlayable(false).build())
        .build()

    private companion object {
        const val SEARCH = "search"
    }
}
