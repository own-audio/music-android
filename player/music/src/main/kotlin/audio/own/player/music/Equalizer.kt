package audio.own.player.music

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * The fixed 6-band graphic EQ layout. Not user-configurable: a graphic EQ's whole appeal is a
 * small, memorable set of bands, not a parametric editor. The same bands as the iOS app and the
 * own.audio server's `eq[]` setting, so a curve means the same everywhere.
 */
object EqBand {
    val standardFrequenciesHz: List<Double> = listOf(60.0, 150.0, 400.0, 1_000.0, 2_400.0, 15_000.0)

    /** Wide enough that adjacent bands (one to two octaves apart) blend rather than notch. */
    const val Q_FACTOR = 1.0

    /** What a slider (bands and preamp) can reach, in dB. */
    const val MIN_GAIN_DB = -12.0
    const val MAX_GAIN_DB = 12.0

    val count: Int get() = standardFrequenciesHz.size

    /** "60", "150", "400", "1K", "2.4K", "15K". */
    fun label(index: Int): String {
        val hz = standardFrequenciesHz[index]
        if (hz >= 1_000) {
            val khz = hz / 1_000
            return if (khz % 1.0 == 0.0) "${khz.toInt()}K" else String.format(Locale.ROOT, "%.1fK", khz)
        }
        return hz.toInt().toString()
    }
}

/**
 * A named starting point for the six sliders. Tapping one is just "set these six numbers"; the
 * server never needs to know. The curves are a reasonable starting shape per name, as in any
 * consumer graphic EQ, not a measurement.
 *
 * [name] is identity: it is what is saved as [EqualizerSettings.presetName]. What a listener
 * reads comes from the app's string resources, keyed by it.
 */
data class EqPreset(val name: String, val bandGainsDb: List<Double>) {
    companion object {
        val FLAT = EqPreset("Flat", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))

        val all: List<EqPreset> = listOf(
            FLAT,
            EqPreset("Bass Boost", listOf(6.0, 4.0, 2.0, 0.0, 0.0, 0.0)),
            EqPreset("Treble Boost", listOf(0.0, 0.0, 0.0, 2.0, 4.0, 6.0)),
            EqPreset("Spoken Word", listOf(-4.0, -2.0, 3.0, 4.0, 3.0, -2.0)),
            EqPreset("Loudness", listOf(5.0, 2.0, 0.0, 0.0, 2.0, 5.0)),
            EqPreset("High Speed 1", listOf(0.0, 0.0, 1.0, 2.0, 3.0, 2.0)),
            EqPreset("High Speed 2", listOf(0.0, -1.0, 1.0, 3.0, 4.0, 3.0)),
        )

        fun named(name: String?): EqPreset? = all.firstOrNull { it.name == name }
    }
}

/** The equalizer curve as it is saved on this device. */
@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preampDb: Double = 0.0,
    val bandGainsDb: List<Double> = List(EqBand.count) { 0.0 },
    /** Null once a slider moved away from a preset: shown as "Custom". */
    val presetName: String? = null,
) {
    /** The preset these gains came from, or null for a custom curve. */
    val preset: EqPreset? get() = EqPreset.named(presetName)
}

/**
 * The equalizer curve, saved on this device. Every change reaches [settings] at once, so the
 * sound follows the slider; saving to [persistence] happens on release ([save]) or on a toggle,
 * preset or reset, not on every step of a drag.
 */
class EqualizerStore(private val persistence: Persistence = InMemoryPersistence()) {
    interface Persistence {
        fun load(): EqualizerSettings?
        fun save(settings: EqualizerSettings)
    }

    class InMemoryPersistence(private var saved: EqualizerSettings? = null) : Persistence {
        override fun load(): EqualizerSettings? = saved
        override fun save(settings: EqualizerSettings) {
            saved = settings
        }
    }

    private val _settings = MutableStateFlow(
        // A curve saved with another band count is from another layout; start flat.
        persistence.load()?.takeIf { it.bandGainsDb.size == EqBand.count } ?: EqualizerSettings(),
    )

    /** The player follows this. */
    val settings: StateFlow<EqualizerSettings> = _settings.asStateFlow()

    fun setEnabled(value: Boolean) {
        _settings.value = _settings.value.copy(enabled = value)
        save()
    }

    fun setPreampDb(value: Double) {
        _settings.value = _settings.value.copy(preampDb = clamp(value))
    }

    fun setBandGainDb(index: Int, value: Double) {
        val gains = _settings.value.bandGainsDb
        if (index !in gains.indices) return
        _settings.value = _settings.value.copy(
            bandGainsDb = gains.toMutableList().also { it[index] = clamp(value) },
            presetName = null,
        )
    }

    fun applyPreset(preset: EqPreset) {
        _settings.value = _settings.value.copy(bandGainsDb = preset.bandGainsDb, presetName = preset.name)
        save()
    }

    /** The Reset button: preamp at 0 and the Flat curve; on or off stays as it was. */
    fun reset() {
        _settings.value = _settings.value.copy(preampDb = 0.0, bandGainsDb = EqPreset.FLAT.bandGainsDb, presetName = EqPreset.FLAT.name)
        save()
    }

    fun save() = persistence.save(_settings.value)

    private fun clamp(db: Double) = db.coerceIn(EqBand.MIN_GAIN_DB, EqBand.MAX_GAIN_DB)
}
