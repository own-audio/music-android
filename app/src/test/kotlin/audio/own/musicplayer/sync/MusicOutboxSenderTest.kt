package audio.own.musicplayer.sync

import audio.own.core.sync.Delivery
import audio.own.core.sync.PendingChange
import audio.own.core.sync.PendingPlay
import audio.own.music.library.BatchedOfflineEdits
import audio.own.music.library.FavoriteKind
import audio.own.music.library.MusicSource
import audio.own.music.library.OfflineEdit
import audio.own.music.library.SourceError
import audio.own.musicplayer.library.FakeMusicSource
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicOutboxSenderTest {
    /** A server that takes changes in one request, as own.audio's `/sync/push`. */
    private class Batched(private val fake: FakeMusicSource = FakeMusicSource()) : MusicSource by fake, BatchedOfflineEdits {
        val pushed = ArrayList<OfflineEdit>()
        var failure: Exception? = null
        override suspend fun push(edits: List<OfflineEdit>) {
            failure?.let { throw it }
            pushed += edits
        }
    }

    private fun favourite(id: String, on: Boolean, kind: FavoriteKind = FavoriteKind.SONG) =
        PendingChange(serverId = "s", itemId = id, op = AppSync.OP_FAVOURITE, kind = kind.name, value = on.toString(), mergeKey = "favourite:$id", clientId = "$id/1", madeAt = 1_000)

    private fun rating(id: String, value: Int) =
        PendingChange(serverId = "s", itemId = id, op = AppSync.OP_RATING, kind = FavoriteKind.ALBUM.name, value = value.toString(), mergeKey = "rating:$id", clientId = "$id/2", madeAt = 2_000)

    @Test
    fun `own_audio gets favourites, ratings and plays in one push`() = runTest {
        val server = Batched()
        val sender = MusicOutboxSender({ server }, { true })
        assertEquals(listOf(Delivery.DONE, Delivery.DONE), sender.sendChanges("s", listOf(favourite("s|t1", true), rating("s|a1", 4))))
        assertEquals(OfflineEdit.Change.Favorite(FavoriteKind.SONG, true), server.pushed[0].change)
        assertEquals(OfflineEdit.Change.Rating(FavoriteKind.ALBUM, 4), server.pushed[1].change)
        assertEquals("s|t1/1", server.pushed[0].clientId)

        sender.sendPlays("s", listOf(PendingPlay("s|t1@5", "s", "s|t1", 5, 200_005, 180_000)))
        val played = server.pushed.last()
        assertEquals("s|t1@5", played.clientId)
        assertEquals(OfflineEdit.Change.Played(java.time.Instant.ofEpochMilli(5), 180_000), played.change)
    }

    @Test(expected = SourceError.Network::class)
    fun `a network failure keeps the batch`() = runTest {
        val server = Batched().apply { failure = SourceError.Network() }
        MusicOutboxSender({ server }, { true }).sendChanges("s", listOf(favourite("s|t1", true)))
    }

    @Test
    fun `other servers get one call each, a refusal is dropped, no network is kept`() = runTest {
        val server = object : MusicSource by FakeMusicSource() {
            val calls = ArrayList<String>()
            override suspend fun setFavorite(kind: FavoriteKind, id: String, isFavorite: Boolean) {
                calls += "fav $id $isFavorite"
                if (id == "s|gone") throw SourceError.NotFound()
                if (id == "s|later") throw IOException("no network")
            }
        }
        val sender = MusicOutboxSender({ server }, { true })
        val answers = sender.sendChanges("s", listOf(favourite("s|t1", false), favourite("s|gone", true), favourite("s|later", true)))
        assertEquals(listOf(Delivery.DONE, Delivery.DROP, Delivery.KEEP), answers)
        assertEquals("fav s|t1 false", server.calls.first())
    }

    @Test
    fun `a removed server's entries are dropped, a server not read yet keeps them`() = runTest {
        assertEquals(listOf(Delivery.DROP), MusicOutboxSender({ null }, { false }).sendChanges("s", listOf(favourite("s|t1", true))))
        val kept = runCatching { MusicOutboxSender({ null }, { true }).sendChanges("s", listOf(favourite("s|t1", true))) }
        assertTrue(kept.exceptionOrNull() is IOException)
    }
}
