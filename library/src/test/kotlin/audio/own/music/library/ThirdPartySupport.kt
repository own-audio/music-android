package audio.own.music.library

import audio.own.kit.jellyfin.ClientInfo
import audio.own.kit.jellyfin.JellyfinClient
import audio.own.kit.jellyfin.Session
import audio.own.kit.subsonic.SubsonicClient
import java.io.IOException
import java.util.Collections
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * A MockWebServer for the Subsonic and Jellyfin sources, with a switch that makes the network
 * fail before anything reaches it (the way a phone without a connection fails).
 */
class KitStub(private val answer: (RecordedRequest) -> MockResponse) : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    @Volatile var networkUp = true

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            if (!networkUp) throw IOException("network down")
            chain.proceed(chain.request())
        }
        .build()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    fun subsonic() = SubsonicClient(server.url("/"), "anna", "secret", http, "own.audio Music/1.0 (Test)")

    fun jellyfin(libraryId: String? = null) = JellyfinClient(server.url("/"), JF_SESSION, JF_INFO, http, libraryId = libraryId)

    /** Subsonic actions asked for, without `.view`. */
    fun actions(): List<String> = requests.map { it.url.encodedPath.removePrefix("/rest/").removeSuffix(".view") }

    fun last(action: String): RecordedRequest = requests.last { it.url.encodedPath == "/rest/$action.view" }

    override fun close() = server.close()

    companion object {
        val JF_SESSION = Session(userId = "u1", userName = "anna", accessToken = "tok", serverId = "s1")
        val JF_INFO = ClientInfo("own.audio Music", "Pixel", "dev-1", "1.0", userAgent = "own.audio Music/1.0 (Test)")
    }
}

/** A Subsonic answer: [body] is the members after `status` (without a leading comma), or empty. */
fun subsonicOk(body: String = ""): MockResponse =
    json("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}""")

fun subsonicFailed(code: Int, message: String): MockResponse =
    json("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""")

/** Answers by Subsonic action name; anything else is a Subsonic "not found". */
fun subsonicServer(answers: Map<String, (RecordedRequest) -> MockResponse>): (RecordedRequest) -> MockResponse = { request ->
    val action = request.url.encodedPath.removePrefix("/rest/").removeSuffix(".view")
    answers[action]?.invoke(request) ?: subsonicFailed(70, "no $action")
}

fun subsonicAlbum(id: String, name: String, artistId: String = "ar1", artist: String = "Band", extra: String = "") =
    """{"id":"$id","name":"$name","artist":"$artist","artistId":"$artistId","songCount":2,"duration":400,"year":2001$extra}"""

fun subsonicSong(id: String, title: String, albumId: String = "al1", extra: String = "") =
    """{"id":"$id","title":"$title","album":"First","albumId":"$albumId","artist":"Band","artistId":"ar1","track":1,"discNumber":1,"duration":200,"genre":"Rock","suffix":"flac","bitRate":900$extra}"""
