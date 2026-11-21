package audio.own.music.library

import audio.own.core.servers.DiskAnswerCache

// The Subsonic and Jellyfin kits know nothing of the app: each defines its own small interfaces
// for saved answers and for logging. These hand them the app's.

/** Saved answers for the Subsonic client, in the server's [DiskAnswerCache]. */
fun DiskAnswerCache.forSubsonic(): audio.own.kit.subsonic.ResponseCache = object : audio.own.kit.subsonic.ResponseCache {
    override fun data(key: String): ByteArray? = get(key)
    override fun store(key: String, data: ByteArray) = put(key, data)
}

/** Saved answers for the Jellyfin client, in the server's [DiskAnswerCache]. */
fun DiskAnswerCache.forJellyfin(): audio.own.kit.jellyfin.ResponseCache = object : audio.own.kit.jellyfin.ResponseCache {
    override fun data(key: String): ByteArray? = get(key)
    override fun store(key: String, data: ByteArray) = put(key, data)
}

/** The kits' messages carry paths only, never a query (it holds the sign-in), so they can go to the app's log as they are. */
fun subsonicLog(error: (String) -> Unit): audio.own.kit.subsonic.NetworkLog = audio.own.kit.subsonic.NetworkLog { error(it) }

fun jellyfinLog(error: (String) -> Unit): audio.own.kit.jellyfin.NetworkLog = audio.own.kit.jellyfin.NetworkLog { error(it) }
