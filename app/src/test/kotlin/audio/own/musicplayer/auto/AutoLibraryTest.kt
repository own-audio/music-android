package audio.own.musicplayer.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.core.downloads.CollectionEntity
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.FavoritesResult
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.SearchResult
import audio.own.music.library.model.Song
import audio.own.musicplayer.downloads.SongRecord
import audio.own.musicplayer.downloads.recordJson
import audio.own.musicplayer.library.FakeMusicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The car's browse tree: the root, each list, the caps, and what a row plays. Ported from CarPlayContentTests. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoLibraryTest {
    private var source: MusicSource? = FakeMusicSource()
    private var snapshot = DownloadsSnapshot(loaded = true)

    private val auto = AutoLibrary(
        library = object : AutoLibrary.Library {
            override val hasServer: Boolean get() = source != null
            override fun source(): MusicSource? = source
            override val downloads: DownloadsSnapshot get() = snapshot
        },
        words = Words,
    )

    private val List<MediaItem>.titles get() = map { it.mediaMetadata.title.toString() }

    @Test
    fun `the root has the six lists, in the iPhone's order`() = runTest {
        val root = auto.children(AutoContent.ROOT)
        assertEquals(listOf("Recently Played", "Recently Added", "Downloads", "Playlists", "Artists", "Favorites"), root.titles)
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true })
    }

    @Test
    fun `with no server the root says to add one on the phone`() = runTest {
        source = null
        val root = auto.children(AutoContent.ROOT)
        assertEquals(listOf("Add a server on your phone"), root.titles)
        assertFalse(root.single().mediaMetadata.isPlayable == true)
    }

    @Test
    fun `a car with four tabs gets three and More with the rest`() = runTest {
        auto.tabLimit = 4
        assertEquals(listOf("Recently Played", "Recently Added", "Downloads", "More"), auto.children(AutoContent.ROOT).titles)
        assertEquals(listOf("Playlists", "Artists", "Favorites"), auto.children(AutoContent.MORE).titles)
    }

    @Test
    fun `album lists are capped at 100, play from the first song and show as a grid`() = runTest {
        val recent = auto.children(AutoContent.RootEntry.RECENTLY_PLAYED.id)
        assertEquals(AutoContent.MAX_ITEMS, recent.size)
        assertTrue(recent.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
        val folder = auto.children(AutoContent.ROOT).first()
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, folder.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        val songs = auto.playable(recent.first().mediaId)!!
        assertEquals(listOf("s|a1-t1", "s|a1-t2", "s|a1-t3"), songs.songs.map { it.id })
        assertNull(songs.startId)
        assertEquals("album:s|a1", songs.containerId)
        val playlists = auto.children(AutoContent.ROOT).first { it.mediaId == AutoContent.RootEntry.PLAYLISTS.id }
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, playlists.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
    }

    @Test
    fun `an artist opens its albums`() = runTest {
        val artists = auto.children(AutoContent.RootEntry.ARTISTS.id)
        assertEquals(listOf("Artist 1"), artists.titles)
        assertTrue(artists.single().mediaMetadata.isBrowsable == true)
        assertEquals(listOf("Album 1", "Album 2"), auto.children(artists.single().mediaId).titles)
    }

    @Test
    fun `a failure is one row that says why`() = runTest {
        (source as FakeMusicSource).failing = SourceError.Network()
        val rows = auto.children(AutoContent.RootEntry.PLAYLISTS.id)
        assertEquals(listOf("failed: Network"), rows.titles)
        assertFalse(rows.single().mediaMetadata.isPlayable == true)
    }

    @Test
    fun `an empty list is one row too`() = runTest {
        source = FakeMusicSource(albums = emptyList(), playlists = emptyList())
        assertEquals(listOf("Nothing here yet"), auto.children(AutoContent.RootEntry.RECENTLY_ADDED.id).titles)
        assertEquals(listOf("No playlists"), auto.children(AutoContent.RootEntry.PLAYLISTS.id).titles)
        assertEquals(listOf("No favorites"), auto.children(AutoContent.RootEntry.FAVORITES.id).titles)
        assertEquals(listOf("Nothing downloaded"), auto.children(AutoContent.RootEntry.DOWNLOADS.id).titles)
    }

    @Test
    fun `favorites start with all favourite songs, then the albums`() = runTest {
        val songs = listOf(Song("s|f1", "One"), Song("s|f2", "Two"))
        source = object : MusicSource by FakeMusicSource() {
            override suspend fun favorites() = FavoritesResult(emptyList(), listOf(Album("s|fa", "Fav Album")), songs)
        }
        val rows = auto.children(AutoContent.RootEntry.FAVORITES.id)
        assertEquals(listOf("Favorite Songs", "Fav Album"), rows.titles)
        assertEquals("2 songs", rows.first().mediaMetadata.subtitle.toString())
        assertEquals(songs, auto.playable(rows.first().mediaId)!!.songs)
        assertEquals(AutoContent.FavoriteRow.Songs(2), AutoContent.favoriteRows(2, List(200) { Album("s|$it", "A") }).first())
        assertEquals(AutoContent.MAX_ITEMS, AutoContent.favoriteRows(2, List(200) { Album("s|$it", "A") }).size)
    }

    @Test
    fun `downloads list the loose songs first, then the collections by name, offline too`() = runTest {
        source = FakeMusicSource().apply { failing = SourceError.Offline() }
        snapshot = downloads()
        val rows = auto.children(AutoContent.RootEntry.DOWNLOADS.id)
        assertEquals(listOf("Downloaded Songs", "Abbey Road", "Zen"), rows.titles)
        assertEquals(listOf("s|loose"), auto.playable(rows[0].mediaId)!!.songs.map { it.id })
        val zen = auto.playable(rows[2].mediaId)!!
        assertEquals(listOf("s|z2", "s|z1"), zen.songs.map { it.id })
        assertEquals("playlist:s|zen", zen.containerId)
    }

    @Test
    fun `search finds albums, playlists and songs, and a song plays its list from there`() = runTest {
        val found = listOf(Song("s|x1", "Road One"), Song("s|x2", "Road Two"))
        source = object : MusicSource by FakeMusicSource(playlists = listOf(Playlist("s|p1", "Road Trip"), Playlist("s|p2", "Gym"))) {
            override suspend fun search(text: String) = SearchResult(emptyList(), listOf(Album("s|a1", "Road Album")), found)
        }
        val items = auto.search("road")
        assertEquals(listOf("Road Album", "Road Trip", "Road One", "Road Two"), items.titles)
        assertEquals("Songs", items.last().mediaMetadata.extras!!.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
        assertTrue(auto.knows("s|x2"))
        val played = auto.playable("s|x2")!!
        assertEquals(found, played.songs)
        assertEquals("s|x2", played.startId)
        assertFalse(auto.knows("s|elsewhere"))
    }

    private fun downloads(): DownloadsSnapshot {
        fun entity(id: String, at: Long) = DownloadEntity(
            key = id, itemId = id, fileId = null, serverId = "s", title = id, extensionHint = null,
            record = recordJson.encodeToString(SongRecord.serializer(), SongRecord.of(Song(id, "Song $id"))), state = DownloadEntity.DONE,
            fileName = "s/$id.mp3", bytes = 10, queuedAt = at, downloadedAt = at,
        )
        val items = listOf("s|loose", "s|z1", "s|z2", "s|r1").mapIndexed { i, id -> id to entity(id, i.toLong()) }.toMap()
        return DownloadsSnapshot(
            items = items,
            collections = listOf(
                DownloadsSnapshot.CollectionView(CollectionEntity("playlist:s|zen", "playlist", "s", "s|zen", "Zen", null, null, null, 0), listOf("s|z2", "s|z1")),
                DownloadsSnapshot.CollectionView(CollectionEntity("album:s|abbey", "album", "s", "s|abbey", "Abbey Road", "The Beatles", "s|abbey", null, 0), listOf("s|r1")),
                DownloadsSnapshot.CollectionView(CollectionEntity("album:s|gone", "album", "s", "s|gone", "Gone", null, null, null, 0), listOf("s|never")),
            ),
            loaded = true,
        )
    }

    private object Words : AutoLibrary.Words {
        override fun title(entry: AutoContent.RootEntry) = when (entry) {
            AutoContent.RootEntry.RECENTLY_PLAYED -> "Recently Played"
            AutoContent.RootEntry.RECENTLY_ADDED -> "Recently Added"
            AutoContent.RootEntry.DOWNLOADS -> "Downloads"
            AutoContent.RootEntry.PLAYLISTS -> "Playlists"
            AutoContent.RootEntry.ARTISTS -> "Artists"
            AutoContent.RootEntry.FAVORITES -> "Favorites"
        }
        override val more = "More"
        override val addServer = "Add a server on your phone"
        override val nothingHere = "Nothing here yet"
        override val noPlaylists = "No playlists"
        override val noArtists = "No artists"
        override val noAlbums = "No albums"
        override val noFavorites = "No favorites"
        override val nothingDownloaded = "Nothing downloaded"
        override val favoriteSongs = "Favorite Songs"
        override val downloadedSongs = "Downloaded Songs"
        override val albums = "Albums"
        override val songs = "Songs"
        override val artists = "Artists"
        override val playlists = "Playlists"
        override fun songCount(count: Int) = "$count songs"
        override fun failure(error: Throwable) = "failed: ${error.javaClass.simpleName}"
    }
}
