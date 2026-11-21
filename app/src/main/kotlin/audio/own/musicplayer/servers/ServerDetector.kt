package audio.own.musicplayer.servers

import audio.own.core.servers.TypedAddress
import audio.own.core.strings.R
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.discover
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UserFacingException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** What answered at a typed address. */
sealed interface Detected {
    /** An own.audio server new enough for this app, at [host]. */
    data class OwnAudio(val host: String, val discovery: Discovery) : Detected

    /** A Subsonic or Jellyfin server: signing in to those comes with later phases. */
    data class Other(val host: String, val kind: String) : Detected
}

/**
 * Finds out what kind of server is at a typed address before anything is saved: each candidate
 * (https first, then http) is asked for own.audio's discovery, then whether it answers as
 * Jellyfin or Subsonic. A wrong guess is given up on after [timeoutMs].
 */
class ServerDetector(
    http: OkHttpClient,
    private val userAgent: String,
    private val log: AppLog,
    private val timeoutMs: Long = 6_000,
) {
    private val quick = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).followRedirects(true).build()

    fun client(host: String): OwnAudioClient = OwnAudioClient(baseUrl = host, http = quick, userAgent = userAgent, requestTimeoutMs = timeoutMs)

    /** The own.audio discovery at [host], or null when nothing like it answers there. */
    suspend fun discovery(host: String): Discovery? = try {
        client(host).discover()
    } catch (e: OwnAudioException) {
        null
    }

    /**
     * @throws UserFacingException with "That doesn't look like a server address." for nothing
     *   to try, "Couldn't reach the server…" when no candidate answered, and the too-old message
     *   for an own.audio server without a new enough `/api/v2`.
     */
    suspend fun detect(typed: String): Detected {
        val candidates = TypedAddress.candidates(typed).filter { it.toHttpUrlOrNull() != null }
        if (candidates.isEmpty()) throw UserFacingException(R.string.error_invalid_address)
        var answered = false
        for (host in candidates) {
            try {
                val found = client(host).discover()
                found.requireV2()
                log.info("servers", "own.audio ${found.version} at ${hostOnly(host)}")
                return Detected.OwnAudio(host, found)
            } catch (e: OwnAudioException.TooOld) {
                throw UserFacingException(R.string.error_too_old, e)
            } catch (e: OwnAudioException.Network) {
                continue
            } catch (e: OwnAudioException) {
                answered = true
            }
            otherKind(host)?.let { return Detected.Other(host, it) }
        }
        throw UserFacingException(if (answered) R.string.error_invalid_address else R.string.error_unreachable)
    }

    /** "jellyfin" or "subsonic" when the address answers as one. */
    private suspend fun otherKind(host: String): String? = withContext(Dispatchers.IO) {
        val base = host.trimEnd('/')
        when {
            get("$base/System/Info/Public")?.let { "\"ServerName\"" in it && "\"Id\"" in it } == true -> "jellyfin"
            get("$base/rest/ping.view?f=json&v=1.16.1&c=own.music")?.contains("subsonic-response") == true -> "subsonic"
            else -> null
        }
    }

    private fun get(url: String): String? = try {
        quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            response.body.string().takeIf { it.length < 64_000 }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun hostOnly(url: String) = url.toHttpUrlOrNull()?.host ?: url
}
