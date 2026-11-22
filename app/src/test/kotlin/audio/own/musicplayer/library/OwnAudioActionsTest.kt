package audio.own.musicplayer.library

import audio.own.kit.ownaudio.IdentifyScope
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.music.library.ArtworkSource
import audio.own.music.library.HouseholdCredit
import audio.own.music.library.HouseholdInfo
import audio.own.music.library.HouseholdInvite
import audio.own.music.library.HouseholdSource
import audio.own.music.library.HouseholdStorage
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UserFacingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnAudioActionsTest {
    private class Household(var refuse: Boolean = false) : MusicSource by FakeMusicSource(), HouseholdSource, ArtworkSource {
        val log = mutableListOf<String>()
        override suspend fun ownSongIds(albumId: String) = listOf("s|t1", "s|t9")
        override suspend fun household(): HouseholdInfo = throw SourceError.Unsupported()
        override suspend fun picture(name: String): ByteArray = ByteArray(0)
        override suspend fun storage() = HouseholdStorage(0, 0, 0, 0)
        override suspend fun credit(): HouseholdCredit? = null
        override suspend fun invites(): List<HouseholdInvite> = emptyList()
        override suspend fun createInvite(): HouseholdInvite = throw SourceError.Unsupported()
        override suspend fun deleteInvite(id: String) {}
        override suspend fun joinHousehold(code: String): HouseholdInfo = throw SourceError.Unsupported()
        override suspend fun setShared(songIds: List<String>, shared: Boolean) {
            if (refuse) throw SourceError.NotAllowed()
            log += "share $songIds $shared"
        }
        override suspend fun setAlbumShared(albumId: String, shared: Boolean): Int {
            if (refuse) throw SourceError.NotAllowed()
            log += "album $albumId $shared"
            return 2
        }
        override suspend fun setShared(playlistId: String, shared: Boolean) {}
        override suspend fun setCover(songIds: List<String>, albumId: String?, image: ByteArray, contentType: String) {
            log += "cover $songIds $albumId $contentType ${image.size}"
        }
        override suspend fun setPicture(artist: Artist, image: ByteArray, contentType: String) { log += "picture ${artist.id}" }
        override suspend fun setCover(playlistId: String, image: ByteArray, contentType: String) {}
    }

    private val mine = Song("s|t1", "Mine", albumId = "s|a1", coverArt = "s|t1~img1", sharing = Sharing.MINE)
    private val shared = Song("s|t2", "Shared", albumId = "s|a1", sharing = Sharing.SHARED)
    private val theirs = Song("s|t3", "Theirs", albumId = "s|a1", sharing = Sharing.HOUSEHOLD)
    private val elsewhere = Song("n|t1", "From Navidrome")

    private fun actions(source: MusicSource, forgotten: MutableList<Collection<String>> = mutableListOf(), synced: MutableList<String> = mutableListOf(), offline: Boolean = false) =
        OwnAudioActions(
            sourceOf = { id -> if (id.startsWith("s|")) source else FakeMusicSource() },
            sourceForServer = { id -> if (id == "s") source else FakeMusicSource() },
            isOffline = { offline },
            syncServer = { synced += it },
            forgetCovers = { forgotten += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun onlyOwnSongsAreSharedAndTheChangeShowsAtOnce() = runBlocking {
        val source = Household()
        val a = actions(source)
        a.setShared(listOf(mine, theirs, elsewhere), true)
        assertEquals(listOf("share [s|t1] true"), source.log)
        assertEquals(Sharing.SHARED, a.sharing(mine))
        assertEquals(Sharing.HOUSEHOLD, a.sharing(theirs))
        a.setShared(listOf(mine), false)
        assertEquals(Sharing.MINE, a.sharing(mine))
    }

    @Test
    fun aRefusalPutsTheSharingBackAndSaysWhy() {
        val source = Household(refuse = true)
        val a = actions(source)
        assertThrows(SourceError.NotAllowed::class.java) { runBlocking { a.setShared(listOf(mine), true) } }
        assertEquals(Sharing.MINE, a.sharing(mine))
        assertTrue(a.sharingChanges.value.isEmpty())
        assertThrows(SourceError.NotAllowed::class.java) { runBlocking { a.setAlbumShared("s|a1", listOf(mine, shared), true) } }
        assertEquals(Sharing.SHARED, a.sharing(shared))
        assertEquals(Sharing.MINE, a.sharing(mine))
    }

    @Test
    fun anAlbumSharesEveryOwnSongOnItAndMarksTheOnesOnScreen() = runBlocking {
        val source = Household()
        val a = actions(source)
        assertEquals(2, a.setAlbumShared("s|a1", listOf(mine, shared, theirs), true))
        assertEquals(listOf("album s|a1 true"), source.log)
        assertEquals(Sharing.SHARED, a.sharing(mine))
        assertEquals(Sharing.HOUSEHOLD, a.sharing(theirs))
    }

    @Test
    fun offeredOnlyWithAHouseholdAndANetwork() {
        val source = Household()
        assertTrue(actions(source).canShare("s"))
        assertTrue(actions(source).canChoosePictures("s"))
        assertEquals(false, actions(source, offline = true).canShare("s"))
        assertEquals(false, actions(source).canShare("n"))
        assertEquals(false, actions(source).canChoosePictures("n"))
    }

    @Test
    fun picturesGoToOwnSongsAndTheChangedCoversAreForgotten() = runBlocking {
        val source = Household()
        val forgotten = mutableListOf<Collection<String>>()
        val a = actions(source, forgotten)
        val jpeg = ByteArray(10)
        a.setArtwork(ArtworkTarget.Songs(listOf(mine, theirs)), jpeg)
        assertEquals("cover [s|t1] null image/jpeg 10", source.log.last())
        assertEquals(setOf("s|t1", "s|t1~img1", "s|a1"), forgotten.last().toSet())

        val album = Album("s|a1", "First", coverArt = "s|a1~img7")
        a.setArtwork(ArtworkTarget.Album(album, listOf(mine, shared)), jpeg)
        assertEquals("every own song of the album, loaded or not", "cover [s|t1, s|t9] s|a1 image/jpeg 10", source.log.last())
        assertTrue(forgotten.last().containsAll(listOf("s|a1", "s|a1~img7", "s|t9")))

        a.setArtwork(ArtworkTarget.ArtistPicture(Artist("s|r1", "Band", coverArt = "s|r1~img2")), jpeg)
        assertEquals("picture s|r1", source.log.last())
        assertEquals(setOf("s|r1", "s|r1~img2"), forgotten.last().toSet())
    }

    @Test
    fun identifyIsOfferedOnSongsNotKnownToBeSomeoneElses() {
        val a = actions(Household())
        assertTrue(a.mayIdentify(mine))
        assertTrue(a.mayIdentify(Song("s|t5", "Unknown owner")))
        assertEquals(false, a.mayIdentify(theirs))
        a.identify(mine)
        assertEquals(OwnAudioRequest.IdentifySong(mine), a.request.value)
        a.dismiss()
        assertNull(a.request.value)
    }

    @Test
    fun undoingASongNeverIdentifiedSaysSoAndOneThatWasSyncsTheCatalogue() {
        class Identifying(val wasIdentified: Boolean) : MusicSource by FakeMusicSource(), audio.own.music.library.IdentifySource {
            override val identifyClient: OwnAudioClient get() = error("not used")
            override fun apiId(id: String) = id.substringAfter('|')
            override suspend fun identifyAvailability() = audio.own.music.library.IdentifyAvailability(charges = false)
            override fun identifyRuns(availability: audio.own.music.library.IdentifyAvailability) = error("not used")
            override fun identifyRun(runId: String) = error("not used")
            override fun albumScope(albumId: String, label: String) = IdentifyScope("album", apiId(albumId), label = label)
            override fun artistScope(artistId: String, label: String) = IdentifyScope("artist", apiId(artistId), label = label)
            override suspend fun searchRecordings(songId: String, title: String, artist: String, album: String) = error("not used")
            override suspend fun identifySong(songId: String, recording: String, release: String?) {}
            override suspend fun undoIdentify(songId: String) = wasIdentified
        }
        val synced = mutableListOf<String>()
        val never = actions(Identifying(false), synced = synced)
        val e = assertThrows(UserFacingException::class.java) { runBlocking { never.undoIdentify(mine) } }
        assertEquals(R.string.not_identified, e.text.id)
        assertTrue(synced.isEmpty())
        runBlocking { actions(Identifying(true), synced = synced).undoIdentify(mine) }
        assertEquals(listOf("s"), synced)
    }
}
