package audio.own.musicplayer.settings

import androidx.core.content.edit
import android.content.SharedPreferences
import audio.own.core.servers.MobileData
import audio.own.core.servers.NetworkState
import audio.own.player.music.CrossfadeRules
import audio.own.player.music.EqualizerSettings
import audio.own.player.music.EqualizerStore
import audio.own.player.music.ReplayGainMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The settings the player and the downloads read: streaming on mobile data, offline, downloads,
 * crossfade, volume leveling, plays.
 */
data class PlaybackSettings(
    val mobileData: MobileData = MobileData.ORIGINAL,
    val saverKbps: Int = DEFAULT_SAVER_KBPS,
    val offlineMode: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val fadeSecs: Int = CrossfadeRules.DEFAULT_SECS.toInt(),
    val replayGain: ReplayGainMode = ReplayGainMode.DEFAULT,
    val reportPlays: Boolean = true,
    val downloadsWifiOnly: Boolean = true,
    /** Keep at Most, in GB; null is no limit. */
    val keepAtMostGb: Int? = null,
    /** Offline, list the whole catalogue (what isn't downloaded greyed out), not only downloads. */
    val offlineWholeLibrary: Boolean = false,
) {
    /** Keep at Most in bytes, as the file sizes are shown (1 GB = 10⁹ bytes). */
    val keepAtMostBytes: Long? get() = keepAtMostGb?.let { it * 1_000_000_000L }

    /** The crossfade the engine is given: null plays gapless. */
    val crossfadeSecs: Double? get() = if (crossfadeEnabled) fadeSecs.toDouble() else null

    /**
     * The cap for new streams on [network]: Wi-Fi gets the original; mobile data with Saver the
     * chosen bitrate; Data Saver keeps streams at 128 kbps whatever the choice.
     */
    fun maxBitrateKbps(network: NetworkState): Int? {
        if (!network.connected || !network.metered) return null
        val chosen = if (mobileData == MobileData.SAVER) saverKbps else null
        return if (network.dataSaver) minOf(chosen ?: DATA_SAVER_KBPS, DATA_SAVER_KBPS) else chosen
    }

    companion object {
        val SAVER_CHOICES = listOf(320, 192, 128)
        const val DEFAULT_SAVER_KBPS = 192
        const val DATA_SAVER_KBPS = 128

        /** Keep at Most: No Limit (null), then these. */
        val KEEP_AT_MOST_CHOICES: List<Int?> = listOf(null, 1, 2, 5, 10, 20, 50)
    }
}

/**
 * Where [PlaybackSettings] and the equalizer curve are kept. Plain key-value storage, read
 * synchronously: the player asks for the bitrate cap on its own thread each time it builds a
 * queue entry, and can't wait for a DataStore read there.
 */
class PlaybackSettingsStore(private val prefs: KeyValues) {
    /** The few calls the store needs from SharedPreferences, so tests keep it in memory. */
    interface KeyValues {
        fun string(key: String): String?
        fun put(key: String, value: String?)
    }

    class Shared(private val prefs: SharedPreferences) : KeyValues {
        override fun string(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit { if (value == null) remove(key) else putString(key, value) }
        }
    }

    class Memory : KeyValues {
        private val values = HashMap<String, String>()
        override fun string(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()
    val current: PlaybackSettings get() = _settings.value

    val equalizer = EqualizerStore(object : EqualizerStore.Persistence {
        override fun load(): EqualizerSettings? = prefs.string(EQUALIZER)?.let { runCatching { json.decodeFromString(EqualizerSettings.serializer(), it) }.getOrNull() }
        override fun save(settings: EqualizerSettings) = prefs.put(EQUALIZER, json.encodeToString(EqualizerSettings.serializer(), settings))
    })

    fun update(change: (PlaybackSettings) -> PlaybackSettings) {
        val next = change(_settings.value).let { it.copy(fadeSecs = it.fadeSecs.coerceIn(CrossfadeRules.MIN_SECS.toInt(), CrossfadeRules.MAX_SECS.toInt())) }
        _settings.value = next
        write(next)
    }

    private fun read(): PlaybackSettings {
        val d = PlaybackSettings()
        return PlaybackSettings(
            mobileData = prefs.string(MOBILE_DATA)?.let { v -> MobileData.entries.firstOrNull { it.name == v } } ?: d.mobileData,
            saverKbps = prefs.string(SAVER)?.toIntOrNull()?.takeIf { it in PlaybackSettings.SAVER_CHOICES } ?: d.saverKbps,
            offlineMode = prefs.string(OFFLINE)?.toBooleanStrictOrNull() ?: d.offlineMode,
            crossfadeEnabled = prefs.string(CROSSFADE)?.toBooleanStrictOrNull() ?: d.crossfadeEnabled,
            fadeSecs = prefs.string(FADE)?.toIntOrNull() ?: d.fadeSecs,
            replayGain = prefs.string(REPLAY_GAIN)?.let { v -> ReplayGainMode.entries.firstOrNull { it.name == v } } ?: d.replayGain,
            reportPlays = prefs.string(REPORT_PLAYS)?.toBooleanStrictOrNull() ?: d.reportPlays,
            downloadsWifiOnly = prefs.string(WIFI_ONLY)?.toBooleanStrictOrNull() ?: d.downloadsWifiOnly,
            keepAtMostGb = prefs.string(KEEP_AT_MOST)?.toIntOrNull()?.takeIf { it in PlaybackSettings.KEEP_AT_MOST_CHOICES },
            offlineWholeLibrary = prefs.string(WHOLE_LIBRARY)?.toBooleanStrictOrNull() ?: d.offlineWholeLibrary,
        )
    }

    private fun write(s: PlaybackSettings) {
        prefs.put(MOBILE_DATA, s.mobileData.name)
        prefs.put(SAVER, s.saverKbps.toString())
        prefs.put(OFFLINE, s.offlineMode.toString())
        prefs.put(CROSSFADE, s.crossfadeEnabled.toString())
        prefs.put(FADE, s.fadeSecs.toString())
        prefs.put(REPLAY_GAIN, s.replayGain.name)
        prefs.put(REPORT_PLAYS, s.reportPlays.toString())
        prefs.put(WIFI_ONLY, s.downloadsWifiOnly.toString())
        prefs.put(KEEP_AT_MOST, s.keepAtMostGb?.toString())
        prefs.put(WHOLE_LIBRARY, s.offlineWholeLibrary.toString())
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val MOBILE_DATA = "mobileData"
        const val SAVER = "saverBitrateKbps"
        const val OFFLINE = "downloadedOnly"
        const val CROSSFADE = "crossfadeEnabled"
        const val FADE = "fadeDurationSecs"
        const val REPLAY_GAIN = "replayGainMode"
        const val REPORT_PLAYS = "scrobblingEnabled"
        const val EQUALIZER = "equalizer"
        const val WIFI_ONLY = "downloadsWifiOnly"
        const val KEEP_AT_MOST = "downloadsCapGb"
        const val WHOLE_LIBRARY = "offlineShowsWholeLibrary"
    }
}
