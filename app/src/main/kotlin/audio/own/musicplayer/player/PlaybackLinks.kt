package audio.own.musicplayer.player

import android.media.MediaCodecList
import audio.own.kit.ownaudio.PlaybackCapabilities
import audio.own.kit.ownaudio.playbackInfo
import audio.own.music.library.MusicSource
import audio.own.music.library.ownaudio.OwnAudioSource
import audio.own.player.core.PlaybackLink
import audio.own.player.core.PlaybackLinkRequest
import audio.own.player.core.PlaybackLinkResolver
import java.io.IOException

/**
 * What this device decodes, as the content types own.audio's `playback-info` knows. Asked of
 * `MediaCodecList` once; ExoPlayer brings its own FLAC, Opus and Vorbis extractors, but the
 * decoders are the system's.
 */
object DeviceCodecs {
    val direct: List<String> by lazy { fromMimeTypes(decoderMimeTypes()) }

    /** Media codec types → the types the server matches a file against. */
    fun fromMimeTypes(codecs: Set<String>): List<String> = buildList {
        if ("audio/mpeg" in codecs) addAll(listOf("audio/mpeg", "audio/mp3"))
        if ("audio/mp4a-latm" in codecs) addAll(listOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/aacp"))
        if ("audio/alac" in codecs) add("audio/x-alac")
        if ("audio/flac" in codecs) addAll(listOf("audio/flac", "audio/x-flac"))
        if ("audio/opus" in codecs) addAll(listOf("audio/opus", "audio/ogg"))
        if ("audio/vorbis" in codecs) addAll(listOf("audio/vorbis", "audio/ogg"))
        if ("audio/raw" in codecs) addAll(listOf("audio/wav", "audio/x-wav", "audio/wave"))
    }.distinct()

    private fun decoderMimeTypes(): Set<String> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .filter { it.startsWith("audio/") }
            .toSet() + "audio/raw"
    } catch (_: Exception) {
        // No codec list (a JVM test): what every Android device since 9 has.
        setOf("audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/opus", "audio/vorbis", "audio/raw")
    }
}

/**
 * The player's link resolver for every server: the song's composite id names its server. For
 * own.audio it asks `POST /playback-info` with what this device decodes and MP3 before AAC as
 * the transcode order (ffmpeg's AAC writes silence as frames some decoders refuse).
 */
class AppLinkResolver(
    private val sourceOf: (String) -> MusicSource?,
    private val direct: () -> List<String> = { DeviceCodecs.direct },
) : PlaybackLinkResolver {
    override suspend fun resolve(request: PlaybackLinkRequest): PlaybackLink {
        val source = sourceOf(request.mediaId) ?: throw IOException("No server for ${request.mediaId}")
        if (source is OwnAudioSource) {
            val info = source.client.playbackInfo(
                kind = "track",
                id = source.apiId(request.mediaId),
                capabilities = PlaybackCapabilities(direct = direct(), transcode = TRANSCODE, maxBitrateKbps = request.maxBitrateKbps),
            )
            return PlaybackLink(info.url, info.expiresAt.toEpochMilli(), info.contentType)
        }
        val link = source.playbackLink(request.mediaId, request.maxBitrateKbps)
        return PlaybackLink(link.url, link.expiresAt?.toEpochMilli(), link.contentType)
    }

    companion object {
        val TRANSCODE = listOf("mp3", "aac")
    }
}
