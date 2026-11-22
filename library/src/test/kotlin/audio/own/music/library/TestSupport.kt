package audio.own.music.library

import androidx.room.Room
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import audio.own.music.library.db.CatalogueDatabase
import java.time.Instant
import java.util.Collections
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment

const val SERVER = "srv"

/** A MockWebServer that answers with [answer] and keeps every request it saw. */
class StubServer(private val answer: (RecordedRequest) -> MockResponse) : AutoCloseable {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
        server.start()
    }

    fun client(signedIn: Boolean = true, isOffline: () -> Boolean = { false }, http: OkHttpClient = OkHttpClient()) = OwnAudioClient(
        baseUrl = server.url("/").toString(),
        http = http,
        userAgent = "own.audio Music/1.0 (Android 16; Test)",
        tokens = TokenSession(if (signedIn) Tokens("a", "r", Instant.now().plusSeconds(3600)) else null),
        isOffline = isOffline,
    )

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    override fun close() = server.close()
}

fun json(text: String, code: Int = 200, type: String = "application/json"): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", type).body(text).build()

fun ndjson(vararg lines: String): MockResponse = json(lines.joinToString("\n") + "\n", type = "application/x-ndjson")

fun problem(code: String, status: Int): MockResponse = json(
    """{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"}""",
    status,
    "application/problem+json",
)

fun notFound(): MockResponse = problem("not_found", 404)

fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

fun RecordedRequest.text(): String = body?.utf8() ?: ""

/** A recorded answer from `src/test/resources/answers` (copied from :kit:ownaudio). */
fun answer(name: String): String =
    StubServer::class.java.getResource("/answers/$name.json")?.readText() ?: error("no answer $name")

fun memoryDatabase(): CatalogueDatabase =
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CatalogueDatabase::class.java).allowMainThreadQueries().build()

const val ME = """{"id":"u","email":"me@example.com","display_name":"Me","family":{"id":"f","role":"admin","can_upload":true}}"""

fun begin(cursor: String) = """{"type":"begin","cursor":"$cursor"}"""

fun end(cursor: String) = """{"type":"end","cursor":"$cursor"}"""

fun record(kind: String, id: String, record: String) = """{"type":"record","kind":"$kind","id":"$id","record":$record}"""

fun contributorJson(id: String, name: String, image: String? = null) =
    """{"id":"$id","name":"$name","sort_name":null,"kind":"group","musicbrainz_artist_id":null,"image":${image?.let { "\"$it\"" } ?: "null"}}"""

fun albumJson(
    id: String,
    title: String,
    credits: String = """[{"contributor_id":"band","role":"artist","position":0,"join_phrase":""}]""",
    releaseDate: String? = "2001-05-01",
    image: String? = null,
    createdAt: String = "2026-10-11T12:00:00Z",
    trackCount: Int = 2,
) = """{"id":"$id","title":"$title","credits":$credits,"release_group_id":"rg","release_date":${releaseDate?.let { "\"$it\"" } ?: "null"},"is_compilation":false,"track_count":$trackCount,"duration_ms":400000,"disc_count":1,"image":${image?.let { "\"$it\"" } ?: "null"},"created_at":"$createdAt"}"""

fun trackJson(
    id: String,
    title: String,
    albumId: String? = "al",
    credits: String = """[{"contributor_id":"band","role":"artist","position":0,"join_phrase":""}]""",
    genres: String = "[]",
    disc: Int? = 1,
    number: Int? = 1,
    owner: String = "u",
    visibility: String = "family",
    contentType: String = "audio/flac",
    image: String? = null,
) = """{"id":"$id","title":"$title","album_id":${albumId?.let { "\"$it\"" } ?: "null"},"recording_id":null,"credits":$credits,"genre_ids":$genres,"disc_number":${disc ?: "null"},"track_number":${number ?: "null"},"duration_ms":200000,"visibility":"$visibility","owner_id":"$owner","content_type":"$contentType","size_bytes":20000000,"image":${image?.let { "\"$it\"" } ?: "null"},"created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""

fun playlistJson(id: String, name: String, owner: String = "u", visibility: String = "private", tracks: Int = 1) =
    """{"id":"$id","name":"$name","description":null,"visibility":"$visibility","owner_id":"$owner","track_count":$tracks,"duration_ms":200000,"image":null,"created_at":"2026-10-11T12:00:00Z","updated_at":"2026-10-11T12:00:00Z"}"""
