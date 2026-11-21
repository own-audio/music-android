package audio.own.player.music

import kotlinx.serialization.Serializable

/**
 * What the music player needs to know about a song. It knows nothing about servers: [id] is
 * opaque and handed back to the playback-link resolver to find the audio.
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSecs: Int? = null,
    /** Opaque, like [id]: whatever the app needs to find this song's cover. */
    val artworkId: String? = null,
    val replayGain: TrackGain? = null,
)

/** ReplayGain values in dB, peaks linear (1.0 = full scale). */
@Serializable
data class TrackGain(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
)
