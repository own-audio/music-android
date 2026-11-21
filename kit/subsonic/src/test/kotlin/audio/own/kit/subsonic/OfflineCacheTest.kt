package audio.own.kit.subsonic

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfflineCacheTest {
    private val stub = Stub { Stub.ok(ALBUM_BODY) }
    private var offline = false
    private val client = SubsonicClient(
        stub.host, "u", "p", stub.http, SubsonicClientTest.USER_AGENT,
        cache = MemoryCache(), isOffline = { offline },
    )

    @After
    fun tearDown() = stub.close()

    @Test
    fun `a failed request falls back to the answer saved earlier`() = runTest {
        client.album("al-1")
        stub.networkUp = false
        assertEquals(listOf("One"), client.album("al-1").second.map { it.title })
    }

    @Test
    fun `offline answers from the cache without a request`() = runTest {
        client.album("al-1")
        offline = true
        client.album("al-1")
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun `offline with nothing saved is Offline, and writes are refused`() = runTest {
        offline = true
        for (block in listOf<suspend () -> Unit>({ client.album("al-2") }, { client.star(songIds = listOf("s-1")) })) {
            try {
                block()
                fail("expected Offline")
            } catch (e: SubsonicError) {
                assertEquals(SubsonicError.Offline, e)
            }
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `the key ignores the per-request salt and token`() {
        val api = SubsonicClient("http://music.example.com".toHttpUrl(), "u", "p", OkHttpClient(), "ua")
        val a = api.cacheKey("getAlbum", listOf("id" to "1"))
        val b = api.cacheKey("getAlbum", listOf("id" to "1"))
        val other = api.cacheKey("getAlbum", listOf("id" to "2"))
        assertEquals(a, b)
        assertNotEquals(a, other)
        assertFalse(a.contains("t=") || a.contains("s="))
    }

    companion object {
        private val ALBUM_BODY = SubsonicClientTest.envelope(""""album":{"id":"al-1","name":"Opera","songCount":1,"song":[{"id":"s-1","title":"One"}]}""")
    }
}
