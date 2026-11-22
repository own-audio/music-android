package audio.own.musicplayer.library

import audio.own.kit.ownaudio.SmartRule
import audio.own.music.library.MusicSource
import audio.own.music.library.PlaylistDetailsSource
import audio.own.music.library.PlaylistWithSongs
import audio.own.music.library.SmartPlaylistInfo
import audio.own.music.library.SmartPlaylistSource
import audio.own.music.library.SmartPresetInfo
import audio.own.music.library.SmartResult
import audio.own.music.library.SourceError
import audio.own.music.library.model.Genre
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.PlaylistEntry
import audio.own.music.library.model.Song
import audio.own.musicplayer.app.Load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An own.audio-like server: playlists with entry ids, a new order, smart playlists and presets. */
class FakeSmartServer(private val base: FakeMusicSource = FakeMusicSource()) : MusicSource by base, PlaylistDetailsSource, SmartPlaylistSource {
    val log = mutableListOf<String>()
    var entries = listOf(
        Song("s|t1", "One", playlistEntryId = "e1"),
        Song("s|t2", "Two", playlistEntryId = "e2"),
        Song("s|t1", "One", playlistEntryId = "e3"),
    )
    var playlistsHere = listOf(
        Playlist("s|p1", "Mine", owner = "alex", songCount = 3),
        Playlist("s|p2", "Sam's", owner = "sam", ownerName = "Sam", songCount = 8),
    )
    var smart = listOf(SmartPlaylistInfo("s|sp1", "Evening", SmartRule(), isShared = true, isMine = true, isFrozen = false))
    var presets = listOf(SmartPresetInfo("genre-station", "Genre Station", SmartRule()), SmartPresetInfo("new-in-the-library", "New", SmartRule()))
    var resolved = SmartResult(listOf(Song("s|t9", "Nine")), candidates = 340, durationMs = 180_000)
    var refuseOrder = false
    val rulesResolved = mutableListOf<SmartRule>()

    override suspend fun playlists(): List<Playlist> = playlistsHere
    override suspend fun playlist(id: String) = PlaylistWithSongs(playlistsHere.first { it.id == id }, entries)
    override suspend fun genres() = listOf(Genre("g2", "rock"), Genre("g1", "Jazz"))
    override suspend fun createPlaylist(name: String, songIds: List<String>) { log += "create $name $songIds" }
    override suspend fun addToPlaylist(id: String, songIds: List<String>) { log += "add $id $songIds" }
    override suspend fun removeFromPlaylist(id: String, entries: List<PlaylistEntry>) { log += "remove $id ${entries.map { it.index to it.entryId }}" }
    override suspend fun renamePlaylist(id: String, name: String) { log += "rename $id $name" }
    override suspend fun deletePlaylist(id: String) { log += "delete $id" }
    override suspend fun setPlaylistDescription(id: String, description: String?) {}
    override suspend fun reorderPlaylist(id: String, entryIds: List<String>) {
        if (refuseOrder) throw SourceError.NotAllowed()
        log += "order $id $entryIds"
    }
    override suspend fun smartPlaylists() = smart
    override suspend fun smartPresets() = presets
    override suspend fun resolve(rule: SmartRule): SmartResult { rulesResolved += rule; return resolved }
    override suspend fun resolveSmartPlaylist(id: String): SmartResult { log += "resolve $id"; return resolved }
    override suspend fun saveSmartPlaylist(name: String, rule: SmartRule, shared: Boolean): SmartPlaylistInfo {
        log += "save $name $shared"
        return SmartPlaylistInfo("s|new", name, rule, shared, isMine = true, isFrozen = false)
    }
    override suspend fun updateSmartPlaylist(id: String, name: String, rule: SmartRule, shared: Boolean): SmartPlaylistInfo {
        log += "update $id $name"
        return SmartPlaylistInfo(id, name, rule, shared, isMine = true, isFrozen = false)
    }
    override suspend fun deleteSmartPlaylist(id: String) { log += "delete smart $id" }
    override suspend fun freezeSmartPlaylist(id: String, name: String?) { log += "freeze $id" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelsTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theTabShowsSmartPlaylistsAndPresetsFirstWhenOnline() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistsViewModel(server, server, isOffline = { false })
        advanceUntilIdle()
        val state = (vm.state.value as Load.Loaded).value
        assertTrue(state.hasSmart)
        assertEquals(listOf("Evening"), state.smart.map { it.name })
        assertEquals(2, state.presets.size)
        assertEquals(2, state.playlists.size)
    }

    @Test
    fun offlineThereAreNoSmartPlaylists() = runTest(main) {
        val server = FakeSmartServer()
        val state = PlaylistsViewModel(server, server, isOffline = { true }).let { advanceUntilIdle(); (it.state.value as Load.Loaded).value }
        assertFalse(state.hasSmart)
        assertEquals(2, state.playlists.size)
    }

    @Test
    fun aServerWithoutSmartPlaylistsShowsOnlyPlaylists() = runTest(main) {
        val vm = PlaylistsViewModel(FakeMusicSource(), null, isOffline = { false })
        advanceUntilIdle()
        assertFalse((vm.state.value as Load.Loaded).value.hasSmart)
    }

    @Test
    fun newPlaylistIsCreatedEmptyAndTheListReadAgain() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistsViewModel(server, server, isOffline = { false })
        vm.create("  Road trip ")
        vm.create("   ")
        advanceUntilIdle()
        assertEquals(listOf("create Road trip []"), server.log)
    }

    @Test
    fun ownerLinesAndOwnership() {
        val mine = Playlist("p", "x", owner = "alex")
        val theirs = Playlist("p", "x", owner = "sam", ownerName = "Sam")
        val ownAudio = Playlist("p", "x", owner = "u2", ownerName = "", isMine = false)
        assertTrue(mine.isOwn("alex"))
        assertNull(mine.otherOwner("alex"))
        assertEquals("Sam", theirs.otherOwner("alex"))
        assertFalse(ownAudio.isOwn("alex"))
        assertNull(ownAudio.otherOwner("alex"))
        assertTrue(Playlist("p", "x").isOwn("alex"))
    }

    @Test
    fun removingGoesByTheEntryAndReadsTheListAgain() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistViewModel(server, server, "s|p1", null, "alex")
        advanceUntilIdle()
        assertTrue(vm.isOwn)
        assertTrue(vm.canReorder)
        vm.remove(2)
        assertEquals(2, (vm.state.value.songs as Load.Loaded).value.size)
        advanceUntilIdle()
        assertEquals("remove s|p1 [(2, e3)]", server.log.single())
    }

    @Test
    fun aDragSendsEveryEntryIdInTheNewOrder() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistViewModel(server, server, "s|p1", null, "alex")
        advanceUntilIdle()
        vm.move(0, 2)
        assertEquals(listOf("e2", "e3", "e1"), (vm.state.value.songs as Load.Loaded).value.map { it.playlistEntryId })
        advanceUntilIdle()
        assertEquals("order s|p1 [e2, e3, e1]", server.log.single())
    }

    @Test
    fun aRefusedOrderIsShownAndTheServersOrderComesBack() = runTest(main) {
        val server = FakeSmartServer().apply { refuseOrder = true }
        val errors = mutableListOf<Throwable>()
        val vm = PlaylistViewModel(server, server, "s|p1", null, "alex", onError = { errors += it })
        advanceUntilIdle()
        vm.move(0, 1)
        advanceUntilIdle()
        assertTrue(errors.single() is SourceError.NotAllowed)
        assertEquals(listOf("e1", "e2", "e3"), (vm.state.value.songs as Load.Loaded).value.map { it.playlistEntryId })
    }

    @Test
    fun someoneElsesPlaylistHasNoEdits() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistViewModel(server, server, "s|p2", null, "alex")
        advanceUntilIdle()
        assertFalse(vm.isOwn)
        assertFalse(vm.canReorder)
    }

    @Test
    fun aSubsonicPlaylistCanBeEditedButNotReordered() = runTest(main) {
        val plain = FakeMusicSource()
        val vm = PlaylistViewModel(plain, plain, "s|p1", null, "alex")
        advanceUntilIdle()
        assertTrue(vm.isOwn)
        assertFalse(vm.canReorder)
        assertFalse(vm.canChooseCover)
        assertFalse(vm.canShare)
    }

    @Test
    fun renameAndDelete() = runTest(main) {
        val server = FakeSmartServer()
        val vm = PlaylistViewModel(server, server, "s|p1", null, "alex")
        vm.rename(" Newer ")
        vm.delete()
        advanceUntilIdle()
        assertEquals(listOf("rename s|p1 Newer", "delete s|p1"), server.log)
        assertTrue(vm.state.value.deleted)
    }

    @Test
    fun addToPlaylistOffersOnlyOwnPlaylistsAndClosesAtOnce() = runTest(main) {
        val server = FakeSmartServer()
        val vm = AddToPlaylistViewModel(server, listOf("s|t1", "s|t2"), "alex")
        advanceUntilIdle()
        assertEquals(listOf("Mine"), (vm.state.value.playlists as Load.Loaded).value.map { it.name })
        vm.add(server.playlistsHere.first())
        assertTrue(vm.state.value.done)
        advanceUntilIdle()
        assertEquals("add s|p1 [s|t1, s|t2]", server.log.single())
    }

    @Test
    fun addToANewPlaylistCreatesItWithTheSongs() = runTest(main) {
        val server = FakeSmartServer()
        val vm = AddToPlaylistViewModel(server, listOf("s|t1"), "alex")
        vm.create("Fresh")
        advanceUntilIdle()
        assertEquals("create Fresh [s|t1]", server.log.single())
    }

    @Test
    fun aSavedSmartPlaylistIsDrawnAndCanBeFrozenAndDeleted() = runTest(main) {
        val server = FakeSmartServer()
        val vm = SmartPlaylistViewModel(server, SmartTarget.Saved(server.smart.first()))
        advanceUntilIdle()
        assertEquals(340, (vm.state.value.result as Load.Loaded).value.candidates)
        vm.load()
        vm.freeze()
        advanceUntilIdle()
        assertTrue(vm.state.value.savedAsPlaylist)
        vm.delete()
        advanceUntilIdle()
        assertTrue(vm.state.value.deleted)
        assertEquals(listOf("resolve s|sp1", "resolve s|sp1", "freeze s|sp1", "delete smart s|sp1"), server.log)
    }

    @Test
    fun aGenreStationAsksForItsGenreFirst() = runTest(main) {
        val server = FakeSmartServer()
        val vm = SmartPlaylistViewModel(server, SmartTarget.Preset(server.presets.first()))
        advanceUntilIdle()
        assertTrue(vm.state.value.needsGenre)
        assertEquals(listOf("Jazz", "rock"), vm.state.value.genres)
        assertTrue(server.rulesResolved.isEmpty())
        vm.chooseGenre("Jazz")
        advanceUntilIdle()
        assertEquals(listOf("jazz"), server.rulesResolved.single().filters.genres?.include)
        val draft = vm.draft { "Genre Station" }
        assertNull(draft.existingId)
        assertEquals("Genre Station: Jazz", draft.name)
        assertEquals(listOf("jazz"), draft.rule.filters.genres?.include)
    }

    @Test
    fun savingAPresetShowsTheSavedOne() = runTest(main) {
        val server = FakeSmartServer()
        val vm = SmartPlaylistViewModel(server, SmartTarget.Preset(server.presets[1]))
        advanceUntilIdle()
        vm.saved(SmartPlaylistInfo("s|new", "Mine now", SmartRule(), false, true, false))
        advanceUntilIdle()
        assertTrue(vm.state.value.target is SmartTarget.Saved)
        assertEquals("resolve s|new", server.log.last())
    }
}
