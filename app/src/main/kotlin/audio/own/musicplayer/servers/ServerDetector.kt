package audio.own.musicplayer.servers

import audio.own.core.servers.TypedAddress
import audio.own.core.strings.R
import audio.own.kit.jellyfin.ClientInfo
import audio.own.kit.jellyfin.JellyfinClient
import audio.own.kit.jellyfin.JellyfinError
import audio.own.kit.jellyfin.ServerInfo
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.discover
import audio.own.kit.subsonic.ServerIdentity
import audio.own.kit.subsonic.SubsonicClient
import audio.own.kit.subsonic.SubsonicError
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UserFacingException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** What answered at a typed address. */
sealed interface Detected {
    /** An own.audio server new enough for this app, at [host]. */
    data class OwnAudio(val host: String, val discovery: Discovery) : Detected

    /** A Jellyfin server; [info] carries its identity and the address it sees itself at. */
    data class Jellyfin(val host: HttpUrl, val info: ServerInfo) : Detected

    /** A Subsonic or OpenSubsonic server (Navidrome, Gonic, …); classic servers name no [identity] type. */
    data class Subsonic(val host: HttpUrl, val identity: ServerIdentity) : Detected
}

/**
 * Finds out what kind of server is at a typed address before anything is saved: own.audio's
 * discovery first (https, then http), then Jellyfin's public system info (https, then http on
 * its port 8096), then a Subsonic `ping`. A wrong guess is given up on after [timeoutMs].
 */
class ServerDetector(
    http: OkHttpClient,
    private val userAgent: String,
    private val log: AppLog,
    private val timeoutMs: Long = 6_000,
) {
    private val quick = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).followRedirects(true).build()

    /** Only the User-Agent of it is sent before signing in. */
    private val anonymous = ClientInfo(client = CLIENT_NAME, device = "", deviceId = "", version = "", userAgent = userAgent)

    fun client(host: String): OwnAudioClient = OwnAudioClient(baseUrl = host, http = quick, userAgent = userAgent, requestTimeoutMs = timeoutMs)

    /** The own.audio discovery at [host], or null when nothing like it answers there. */
    suspend fun discovery(host: String): Discovery? = try {
        client(host).discover()
    } catch (e: OwnAudioException) {
        null
    }

    /** Jellyfin's public info at exactly [host], or null. */
    suspend fun jellyfinInfo(host: HttpUrl): ServerInfo? = try {
        JellyfinClient.serverInfo(host, anonymous, quick)
    } catch (e: JellyfinError) {
        null
    }

    /** What a Subsonic server at exactly [host] says about itself, or null when none answers. */
    suspend fun subsonicIdentity(host: HttpUrl): ServerIdentity? = try {
        SubsonicClient(host, "", "", quick, userAgent).serverIdentity()
    } catch (e: SubsonicError) {
        null
    }

    /**
     * @throws UserFacingException with "That doesn't look like a server address." for nothing
     *   to try or an address where something answered but no music server, "Couldn't reach the
     *   server…" when nothing answered at all, and the too-old message for an own.audio server
     *   without a new enough `/api/v2`.
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
        }
        val jellyfin = findJellyfin(typed) { answered = true }
        if (jellyfin != null) return jellyfin
        for (candidate in candidates) {
            val host = SubsonicClient.normalizeHost(candidate) ?: continue
            try {
                val identity = SubsonicClient(host, "", "", quick, userAgent).serverIdentity()
                log.info("servers", "Subsonic server (${identity.type ?: "classic"}) at ${host.host}")
                return Detected.Subsonic(host, identity)
            } catch (e: SubsonicError.Network) {
                continue
            } catch (e: SubsonicError) {
                answered = true
            }
        }
        throw UserFacingException(if (answered) R.string.error_invalid_address else R.string.error_unreachable)
    }

    /**
     * The Jellyfin server at [typed], trying each address Jellyfin's own rules make of it; null
     * when none answers as Jellyfin. [onAnswer] hears of an address that answered with something
     * else.
     */
    suspend fun findJellyfin(typed: String, onAnswer: () -> Unit = {}): Detected.Jellyfin? {
        for (host in JellyfinClient.candidateHosts(typed)) {
            try {
                val info = JellyfinClient.serverInfo(host, anonymous, quick)
                log.info("servers", "Jellyfin ${info.version} at ${host.host}")
                return Detected.Jellyfin(host, info)
            } catch (e: JellyfinError.Network) {
                continue
            } catch (e: JellyfinError) {
                onAnswer()
            }
        }
        return null
    }

    private fun hostOnly(url: String) = url.toHttpUrlOrNull()?.host ?: url

    companion object {
        /** How the app names itself to Jellyfin and Subsonic servers (their dashboards show it). */
        const val CLIENT_NAME = "own.audio Music"
    }
}
