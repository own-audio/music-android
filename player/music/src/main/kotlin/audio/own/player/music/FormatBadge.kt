package audio.own.player.music

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.math.abs

/**
 * What the format badge under the player's title says: "FLAC · 44.1 kHz · 16-bit" for lossless
 * audio, "MP3 320" for lossy. Codec names and units are the same in every language, as on iOS.
 */
data class FormatBadge(
    val codec: String,
    val isLossless: Boolean,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val bitrateKbps: Int? = null,
) {
    /** The badge text; a ReplayGain adjustment other than zero is appended ("+2.5 dB"). */
    fun label(replayGainDb: Double = 0.0): String {
        val parts = mutableListOf<String>()
        if (isLossless) {
            parts += codec
            sampleRateHz?.let { parts += kiloHertz(it) }
            bitsPerSample?.let { parts += "$it-bit" }
        } else {
            parts += if (bitrateKbps != null) "$codec $bitrateKbps" else codec
        }
        if (abs(replayGainDb) >= 0.05) parts += String.format(Locale.ROOT, "%+.1f dB", replayGainDb)
        return parts.joinToString(" · ")
    }

    private fun kiloHertz(hz: Int): String {
        val khz = hz / 1000.0
        val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else String.format(Locale.ROOT, "%.1f", khz)
        return "$text kHz"
    }

    companion object {
        /** From the format ExoPlayer selected; null for anything that isn't audio. */
        @UnstableApi
        fun from(format: Format): FormatBadge? {
            val mime = format.sampleMimeType ?: return null
            if (!MimeTypes.isAudio(mime)) return null
            val (codec, lossless) = when (mime) {
                MimeTypes.AUDIO_FLAC -> "FLAC" to true
                MimeTypes.AUDIO_ALAC -> "ALAC" to true
                MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> "WAV" to true
                MimeTypes.AUDIO_MPEG -> "MP3" to false
                MimeTypes.AUDIO_AAC -> "AAC" to false
                MimeTypes.AUDIO_OPUS -> "Opus" to false
                MimeTypes.AUDIO_VORBIS -> "Vorbis" to false
                MimeTypes.AUDIO_AC3 -> "AC-3" to false
                MimeTypes.AUDIO_E_AC3 -> "E-AC-3" to false
                else -> mime.substringAfter('/').uppercase(Locale.ROOT) to false
            }
            val bitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
                .firstOrNull { it != Format.NO_VALUE && it > 0 }
                ?.let { (it + 500) / 1000 }
            return FormatBadge(
                codec = codec,
                isLossless = lossless,
                sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE && it > 0 },
                bitsPerSample = bits(format.pcmEncoding),
                bitrateKbps = bitrate,
            )
        }

        @UnstableApi
        private fun bits(encoding: Int): Int? = when (encoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }
}
