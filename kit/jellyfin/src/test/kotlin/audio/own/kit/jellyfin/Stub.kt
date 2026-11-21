package audio.own.kit.jellyfin

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A MockWebServer answering every request through [handler], with a log of what was asked and a
 * switch that makes the network fail before anything reaches the server.
 */
class Stub(private val handler: (RecordedRequest) -> MockResponse) : AutoCloseable {
    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var networkUp = true

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
                return handler(request)
            }
        }
        server.start()
    }

    val host: HttpUrl get() = server.url("/")

    fun url(path: String): HttpUrl = server.url(path)

    /** The first request's query parameters, all values per name. */
    fun firstQuery(): Map<String, List<String?>> = requests.first().url.let { url ->
        url.queryParameterNames.associateWith { url.queryParameterValues(it) }
    }

    override fun close() {
        server.close()
    }

    companion object {
        fun ok(body: String, code: Int = 200): MockResponse = MockResponse.Builder().code(code).body(body).build()
    }
}

class MemoryCache : ResponseCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun data(key: String): ByteArray? = map[key]

    override fun store(key: String, data: ByteArray) {
        map[key] = data
    }
}
