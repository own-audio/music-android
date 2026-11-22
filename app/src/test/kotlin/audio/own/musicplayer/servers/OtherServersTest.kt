package audio.own.musicplayer.servers

import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.ServerAuth
import audio.own.core.strings.R
import audio.own.musicplayer.FakeOwnAudio.Companion.json
import audio.own.musicplayer.ModelHarness
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UserFacingException
import audio.own.musicplayer.eventually
import audio.own.musicplayer.mainThread
import audio.own.musicplayer.onboarding.QuickConnectViewModel
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import audio.own.musicplayer.R as AppR

/** A pretend Jellyfin: public info, password sign-in, Quick Connect, the session, libraries. */
class FakeJellyfin : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** A copy to search: the server adds to [requests] from its own thread meanwhile. */
    fun seen(): List<RecordedRequest> = synchronized(requests) { requests.toList() }
    var password = "pw"
    var quickConnect = true
    var approveAfter = 2
    private var checks = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    private fun answer(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "/System/Info/Public" -> json("""{"Id":"jf-1","ServerName":"Den","Version":"10.11.2","LocalAddress":"http://192.168.1.20:8096"}""")
        "/Users/AuthenticateByName" ->
            if (request.body?.utf8()?.contains("\"Pw\":\"$password\"") == true) json(AUTH) else MockResponse.Builder().code(401).build()
        "/QuickConnect/Enabled" -> json(quickConnect.toString())
        "/QuickConnect/Initiate" -> json("""{"Secret":"sec","Code":"123456","Authenticated":false}""")
        "/QuickConnect/Connect" -> json("""{"Secret":"sec","Code":"123456","Authenticated":${++checks >= approveAfter}}""")
        "/Users/AuthenticateWithQuickConnect" -> json(AUTH)
        "/Users/Me" -> json("""{"Id":"u-1"}""")
        "/Sessions/Logout" -> MockResponse.Builder().code(204).build()
        "/UserViews" -> json("""{"Items":[{"Id":"lib-1","Name":"Music","CollectionType":"music"},{"Id":"lib-2","Name":"Films","CollectionType":"movies"},{"Id":"lib-3","Name":"Vinyl","CollectionType":"music"}]}""")
        else -> MockResponse.Builder().code(404).build()
    }

    override fun close() = server.close()

    companion object {
        const val AUTH = """{"User":{"Id":"u-1","Name":"alex"},"AccessToken":"tok-1","ServerId":"jf-1"}"""
    }
}

/** A pretend Subsonic server (Navidrome by default): `ping` with token sign-in. */
class FakeSubsonic : AutoCloseable {
    val server = MockWebServer()
    var type = "navidrome"
    var user = "alex"
    var password = "pw"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.startsWith("/rest/")) return MockResponse.Builder().code(404).body("<html></html>").build()
                val u = request.url.queryParameter("u")
                val t = request.url.queryParameter("t")
                val s = request.url.queryParameter("s").orEmpty()
                // Ampache answers every token sign-in with 40, right password or not.
                val right = u == user && t == md5(password + s) && type != "ampache"
                val head = """"status":"${if (right) "ok" else "failed"}","version":"1.16.1","type":"$type","serverVersion":"0.64","openSubsonic":true"""
                val body = if (right) "{\"subsonic-response\":{$head}}" else "{\"subsonic-response\":{$head,\"error\":{\"code\":40,\"message\":\"Wrong username or password\"}}}"
                return json(body)
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    override fun close() = server.close()

    private fun md5(text: String) = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

class OtherServersTest {
    private val jellyfin = FakeJellyfin()
    private val subsonic = FakeSubsonic()
    private val harness = ModelHarness()
    private val model get() = harness.model

    @After
    fun tearDown() {
        jellyfin.close()
        subsonic.close()
        harness.close()
        Dispatchers.resetMain()
    }

    private fun expectMessage(id: Int, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected a failure")
        } catch (e: UserFacingException) {
            assertEquals(id, e.text.id)
        }
    }

    @Test
    fun jellyfinIsDetectedAndSavedWithItsToken() = runBlocking {
        model.signIn(jellyfin.url, "alex", "pw", "")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.JELLYFIN, saved.kind)
        assertEquals("Den", saved.displayName)
        assertEquals("jf-1", saved.serverIdentity)
        assertEquals(ServerAuth.Jellyfin("alex", "u-1", "tok-1"), saved.auth)
        assertEquals(listOf("http://192.168.1.20:8096"), saved.addresses.filter { it.origin == AddressOrigin.SERVER }.map { it.url })
        val auth = jellyfin.seen().first { it.url.encodedPath == "/Users/AuthenticateByName" }.headers["Authorization"].orEmpty()
        assertTrue(auth.startsWith("MediaBrowser Client=\"own.audio Music\""))
        assertTrue("DeviceId=\"own-music-test\"" in auth)
        assertEquals(saved.id, model.state.value.activeId)
        assertEquals("tok-1", model.jellyfinClient(saved.id)?.session?.accessToken)
    }

    @Test
    fun aWrongJellyfinPasswordSavesNothing() {
        expectMessage(R.string.error_rejected) { model.signIn(jellyfin.url, "alex", "nope", "") }
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun removingAJellyfinServerEndsItsSession() = runBlocking {
        model.signIn(jellyfin.url, "alex", "pw", "")
        model.remove(model.state.value.activeId!!)
        eventually { jellyfin.seen().any { it.url.encodedPath == "/Sessions/Logout" } }
        assertTrue(harness.store.loadAll().isEmpty())
    }

    @Test
    fun theMusicLibrariesAreListedAndTheChoiceKept() = runBlocking {
        model.signIn(jellyfin.url, "alex", "pw", "")
        val id = model.state.value.activeId!!
        assertEquals(listOf("Music", "Vinyl"), model.musicLibraries(id).map { it.name })
        model.setLibrary(id, "lib-3")
        assertEquals("lib-3", harness.store.loadAll().single().libraryId)
        assertEquals("lib-3", model.jellyfinClient(id)?.libraryId)
        assertEquals(1, harness.cleared)
        model.setLibrary(id, null)
        assertNull(model.jellyfinClient(id)?.libraryId)
    }

    @Test
    fun subsonicIsDetectedAndKeepsThePassword() = runBlocking {
        model.signIn(subsonic.url, "alex", "pw", "Home")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.SUBSONIC, saved.kind)
        assertEquals("Home", saved.displayName)
        assertEquals(ServerAuth.Subsonic("alex", "pw"), saved.auth)
        assertEquals("alex", model.subsonicClient(saved.id)?.username)
    }

    @Test
    fun aWrongSubsonicPasswordIsRefused() {
        expectMessage(R.string.error_rejected) { model.signIn(subsonic.url, "alex", "wrong", "") }
    }

    @Test
    fun ampacheGetsTheTokenSignInMessage() {
        subsonic.type = "ampache"
        expectMessage(AppR.string.error_token_sign_in_refused) { model.signIn(subsonic.url, "alex", "pw", "") }
    }

    @Test
    fun checkNowAsksJellyfinAndSubsonicTheirOwnWay() = runBlocking {
        model.signIn(subsonic.url, "alex", "pw", "")
        val id = model.state.value.activeId!!
        model.checkNow(id)
        assertEquals(ConnectionCheck.Status.OK, model.state.value.checks[id]?.status)
        assertTrue(model.state.value.connections[id] is ConnectionState.Connected)
    }

    @Test
    fun quickConnectShowsTheCodeAndSavesOnceApproved() = runBlocking {
        Dispatchers.setMain(mainThread())
        val vm = QuickConnectViewModel(model, jellyfin.url, "", AppLog(null, echo = false), pollMs = 10)
        eventually { vm.state.value.done || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals("123456", vm.state.value.code)
        assertEquals(ServerKinds.JELLYFIN, harness.store.loadAll().single().kind)
    }

    @Test
    fun quickConnectTurnedOffSaysSo() = runBlocking {
        Dispatchers.setMain(mainThread())
        jellyfin.quickConnect = false
        val vm = QuickConnectViewModel(model, jellyfin.url, "", AppLog(null, echo = false), pollMs = 10)
        eventually { vm.state.value.error != null }
        assertEquals(AppR.string.quick_connect_off, vm.state.value.error?.id)
    }

    @Test
    fun quickConnectWhereThereIsNoJellyfin() = runBlocking {
        Dispatchers.setMain(mainThread())
        val vm = QuickConnectViewModel(model, subsonic.url, "", AppLog(null, echo = false), pollMs = 10)
        eventually { vm.state.value.error != null }
        assertEquals(AppR.string.quick_connect_no_jellyfin, vm.state.value.error?.id)
        vm.cancel()
    }
}
