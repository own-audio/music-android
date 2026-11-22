package audio.own.musicplayer.library

import audio.own.music.library.AlbumSort
import audio.own.music.library.model.Album
import audio.own.music.library.SourceError
import audio.own.musicplayer.app.Load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelsTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun homeLoadsTheThreeShelves() = runTest(main) {
        val source = FakeMusicSource()
        val vm = HomeViewModel(source, isOffline = { false })
        advanceUntilIdle()
        val home = (vm.state.value as Load.Loaded).value
        assertEquals(20, home.recent.size)
        assertEquals(20, home.newest.size)
        assertEquals(1, home.playlists.size)
    }

    @Test
    fun homeShowsTheOtherShelvesWhenRecentlyPlayedFails() = runTest(main) {
        val source = FakeMusicSource().apply { recentFails = true }
        val vm = HomeViewModel(source, isOffline = { false })
        advanceUntilIdle()
        val home = (vm.state.value as Load.Loaded).value
        assertTrue(home.recent.isEmpty())
        assertEquals(20, home.newest.size)
    }

    @Test
    fun homeOfflineAsksTheServerNothing() = runTest(main) {
        val source = FakeMusicSource()
        val vm = HomeViewModel(source, isOffline = { true })
        advanceUntilIdle()
        assertTrue((vm.state.value as Load.Loaded).value.isEmpty)
        assertTrue(source.calls.isEmpty())
    }

    // Found on the emulator: the network went while the Search tab was open; back on Home the
    // shelf said Downloaded Albums but still showed the server's newest albums.
    @Test
    fun homeShowsTheDownloadsAfterTheNetworkWentWhileAwayFromIt() = runTest(main) {
        var offline = false
        val downloaded = HomeContentState(newest = listOf(Album("s|dl", "Downloaded")))
        val vm = HomeViewModel(FakeMusicSource(), isOffline = { offline }, offlineHome = { downloaded })
        vm.showFor(1L to false)
        advanceUntilIdle()
        assertEquals(20, (vm.state.value as Load.Loaded).value.newest.size)
        offline = true
        // Home's screen was gone meanwhile; it comes back seeing the new state for the first time.
        vm.showFor(1L to true)
        advanceUntilIdle()
        assertEquals(listOf("s|dl"), (vm.state.value as Load.Loaded).value.newest.map { it.id })
        // The same state again asks nothing.
        vm.showFor(1L to true)
        advanceUntilIdle()
        assertEquals(listOf("s|dl"), (vm.state.value as Load.Loaded).value.newest.map { it.id })
    }

    @Test
    fun homeFailsWithASentence() = runTest(main) {
        val source = FakeMusicSource().apply { failing = SourceError.Network() }
        val vm = HomeViewModel(source, isOffline = { false })
        advanceUntilIdle()
        assertTrue(vm.state.value is Load.Failed)
    }

    @Test
    fun shuffleAllPlaysAHundredRandomSongs() = runTest(main) {
        val source = FakeMusicSource()
        val vm = HomeViewModel(source, isOffline = { false })
        var played = 0
        vm.shuffleAll { played = it.size }
        advanceUntilIdle()
        assertEquals(100, played)
        assertTrue("random 100 null" in source.calls)
        assertFalse(vm.shuffling.value)
    }

    @Test
    fun shuffleAllReportsAFailure() = runTest(main) {
        val source = FakeMusicSource()
        var error: Throwable? = null
        val vm = HomeViewModel(source, isOffline = { false }, onError = { error = it })
        advanceUntilIdle()
        source.failing = SourceError.Network()
        vm.shuffleAll { }
        advanceUntilIdle()
        assertTrue(error is SourceError.Network)
    }

    @Test
    fun albumsPageUntilAShortPage() = runTest(main) {
        val source = FakeMusicSource()
        val vm = AlbumsViewModel(source, AlbumSort.NAME)
        advanceUntilIdle()
        assertEquals(60, (vm.state.value.albums as Load.Loaded).value.size)
        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(150, (vm.state.value.albums as Load.Loaded).value.size)
        assertTrue(vm.state.value.reachedEnd)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("albums NAME 60 0", "albums NAME 60 60", "albums NAME 60 120"), source.calls)
    }

    @Test
    fun randomAlbumsAreOnePage() = runTest(main) {
        val vm = AlbumsViewModel(FakeMusicSource(), AlbumSort.RANDOM)
        advanceUntilIdle()
        assertTrue(vm.state.value.reachedEnd)
    }

    @Test
    fun aNewSortStartsAgain() = runTest(main) {
        val source = FakeMusicSource()
        val vm = AlbumsViewModel(source, AlbumSort.NAME)
        advanceUntilIdle()
        vm.setSort(AlbumSort.NEWEST)
        advanceUntilIdle()
        assertEquals(AlbumSort.NEWEST, vm.state.value.sort)
        assertEquals("albums NEWEST 60 0", source.calls.last())
    }

    @Test
    fun albumLoadsItsSongs() = runTest(main) {
        val vm = AlbumViewModel(FakeMusicSource(), "s|a3")
        advanceUntilIdle()
        assertEquals(3, (vm.state.value as Load.Loaded).value.songs.size)
    }

    @Test
    fun songsPageByHundreds() = runTest(main) {
        val vm = SongsViewModel(FakeMusicSource())
        advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(230, (vm.state.value.songs as Load.Loaded).value.size)
        assertTrue(vm.state.value.reachedEnd)
    }

    @Test
    fun searchWaitsForAPauseInTyping() = runTest(main) {
        val source = FakeMusicSource()
        val vm = SearchViewModel(source, isOffline = { false })
        vm.setQuery("Al")
        advanceTimeBy(100)
        vm.setQuery("Album 1")
        advanceUntilIdle()
        assertEquals(listOf("search Album 1"), source.calls)
        assertEquals(3, vm.state.value.result?.albums?.size)
    }

    @Test
    fun clearingTheSearchClearsTheResult() = runTest(main) {
        val vm = SearchViewModel(FakeMusicSource(), isOffline = { false })
        vm.setQuery("Album")
        advanceUntilIdle()
        vm.setQuery("  ")
        assertEquals(null, vm.state.value.result)
    }

    @Test
    fun searchFailureIsASentence() = runTest(main) {
        val source = FakeMusicSource().apply { failing = SourceError.Offline() }
        val vm = SearchViewModel(source, isOffline = { false })
        vm.setQuery("x")
        advanceUntilIdle()
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun artistPlaysEveryAlbumInOrder() = runTest(main) {
        val vm = ArtistViewModel(FakeMusicSource(), "s|r1")
        advanceUntilIdle()
        var ids = emptyList<String>()
        vm.playAll { songs -> ids = songs.map { it.id } }
        advanceUntilIdle()
        assertEquals(6, ids.size)
        assertEquals("s|a1-t1", ids.first())
        assertEquals("s|a2-t3", ids.last())
    }

    // An artist with one song on a compilation: Play played the whole compilation.
    @Test
    fun artistPlaysOnlyTheirOwnSongsFromSomeoneElsesAlbum() = runTest(main) {
        val albums = listOf(
            Album("s|a1", "Own Album", artist = "Band", artistId = "s|r1", songCount = 3),
            Album("s|a2", "Hits", artist = "Various Artists", artistId = "s|va", songCount = 3),
        )
        val vm = ArtistViewModel(FakeMusicSource(albums, songArtists = mapOf("s|a2-t2" to "s|r1")), "s|r1")
        advanceUntilIdle()
        var ids = emptyList<String>()
        vm.playAll { songs -> ids = songs.map { it.id } }
        advanceUntilIdle()
        assertEquals(listOf("s|a1-t1", "s|a1-t2", "s|a1-t3", "s|a2-t2"), ids)
    }

    @Test
    fun genreShufflesAHundredOfItsSongs() = runTest(main) {
        val source = FakeMusicSource()
        val vm = GenreViewModel(source, audio.own.music.library.model.Genre("g1", "Jazz"))
        vm.shuffle { }
        advanceUntilIdle()
        assertTrue("random 100 g1" in source.calls)
    }
}
