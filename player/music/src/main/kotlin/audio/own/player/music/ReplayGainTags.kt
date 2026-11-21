package audio.own.player.music

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.Locale

/**
 * ReplayGain values read from a file's own tags, for when the server reports none (own.audio
 * v2 carries no gain fields yet). Where each tagger puts them:
 *
 * - MP3: ID3v2 `TXXX` frames described `REPLAYGAIN_TRACK_GAIN` and so on;
 * - FLAC, Ogg Vorbis: Vorbis comments of the same names;
 * - M4A: iTunes freeform atoms (`----:com.apple.iTunes:replaygain_track_gain`);
 * - Opus: `R128_TRACK_GAIN`, a Q7.8 number relative to −23 LUFS rather than ReplayGain's
 *   −18, so it is shifted by 5 dB.
 *
 * Values look like "-6.20 dB" and "0.988547"; anything that doesn't read as a number is ignored.
 */
@UnstableApi
object ReplayGainTags {
    private const val TRACK_GAIN = "replaygain_track_gain"
    private const val ALBUM_GAIN = "replaygain_album_gain"
    private const val TRACK_PEAK = "replaygain_track_peak"
    private const val ALBUM_PEAK = "replaygain_album_peak"
    private const val R128_TRACK = "r128_track_gain"
    private const val R128_ALBUM = "r128_album_gain"

    /** The gain the tags in [metadata] describe, or null when they carry none. */
    fun parse(metadata: Iterable<Metadata?>): TrackGain? {
        val values = HashMap<String, String>()
        for (entries in metadata) {
            if (entries == null) continue
            for (i in 0 until entries.length()) {
                val (key, value) = keyValue(entries.get(i)) ?: continue
                values.putIfAbsent(key.lowercase(Locale.ROOT), value)
            }
        }
        val gain = TrackGain(
            trackGainDb = number(values[TRACK_GAIN]) ?: number(values[R128_TRACK])?.let(::fromR128),
            albumGainDb = number(values[ALBUM_GAIN]) ?: number(values[R128_ALBUM])?.let(::fromR128),
            trackPeak = number(values[TRACK_PEAK]),
            albumPeak = number(values[ALBUM_PEAK]),
        )
        return gain.takeIf { it.trackGainDb != null || it.albumGainDb != null }
    }

    fun parse(metadata: Metadata?): TrackGain? = parse(listOf(metadata))

    private fun keyValue(entry: Metadata.Entry): Pair<String, String>? = when (entry) {
        is TextInformationFrame ->
            if (entry.id == "TXXX" && entry.description != null) entry.description!! to entry.values.firstOrNull().orEmpty() else null
        is VorbisComment -> entry.key to entry.value
        is InternalFrame -> entry.description to entry.text
        else -> null
    }

    private val numberPattern = Regex("""[-+]?\d+(?:\.\d+)?""")

    internal fun number(text: String?): Double? = text?.let { numberPattern.find(it)?.value?.toDoubleOrNull() }

    private fun fromR128(q78: Double) = q78 / 256.0 + 5.0
}
