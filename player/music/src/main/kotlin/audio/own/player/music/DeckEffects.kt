package audio.own.player.music

import android.media.audiofx.DynamicsProcessing
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The gain stages of one player deck: the equalizer curve and the part of a ReplayGain
 * adjustment that the player's volume cannot give (volume only attenuates).
 */
interface DeckEffects {
    fun apply(eq: EqualizerSettings, boostDb: Double)
    fun release()
}

/** Makes the effects for an audio session; null where the device has none. */
fun interface DeckEffectsFactory {
    fun create(audioSessionId: Int): DeckEffects?
}

/** The pure numbers behind the equalizer and the ReplayGain volume, testable without audio. */
object GainMath {
    /**
     * `DynamicsProcessing`'s EQ bands are given by their **upper cutoff**, not their centre: each
     * band covers from the previous cutoff up to its own. So the 60/150/400/1k/2.4k/15k centres of
     * the graphic EQ become cutoffs at the geometric midpoints between them, the last at 20 kHz.
     */
    val cutoffsHz: List<Float> = EqBand.standardFrequenciesHz.let { centres ->
        centres.indices.map { i ->
            if (i == centres.lastIndex) 20_000f else sqrt(centres[i] * centres[i + 1]).toFloat()
        }
    }

    /** dB to a linear factor. */
    fun linear(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Splits a ReplayGain adjustment into the player volume (0…1) and what is left to boost in
     * the effects: a quiet song's positive gain cannot be had from volume.
     */
    fun split(gainDb: Double): Pair<Float, Double> =
        if (gainDb <= 0) linear(gainDb).toFloat() to 0.0 else 1f to gainDb

    /** The input gain of the effects: the preamp (when the EQ is on) plus the ReplayGain boost. */
    fun inputGainDb(eq: EqualizerSettings, boostDb: Double): Double = (if (eq.enabled) eq.preampDb else 0.0) + boostDb

    /** Whether the effects are needed at all; otherwise none run, and the audio is untouched. */
    fun needsEffects(eq: EqualizerSettings, boostDb: Double): Boolean =
        boostDb > 0 || (eq.enabled && (eq.preampDb != 0.0 || eq.bandGainsDb.any { it != 0.0 }))
}

/**
 * The 6-band EQ, input gain and a limiter on the deck's audio session, with
 * `DynamicsProcessing` (API 28+). The limiter is on only while something boosts, so a curve or
 * a ReplayGain boost cannot clip; at flat settings the effect is released altogether.
 */
class DynamicsProcessingEffects(private val audioSessionId: Int) : DeckEffects {
    private var effect: DynamicsProcessing? = null

    override fun apply(eq: EqualizerSettings, boostDb: Double) {
        if (!GainMath.needsEffects(eq, boostDb)) {
            release()
            return
        }
        val dp = effect ?: create() ?: return
        GainMath.cutoffsHz.forEachIndexed { i, cutoff ->
            val gain = if (eq.enabled) eq.bandGainsDb.getOrElse(i) { 0.0 } else 0.0
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff, gain.toFloat()))
        }
        val input = GainMath.inputGainDb(eq, boostDb)
        dp.setInputGainAllChannelsTo(input.toFloat())
        val boosts = input > 0 || (eq.enabled && eq.bandGainsDb.any { it > 0 })
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, boosts, 0, 1f, 60f, 10f, -1f, 0f),
        )
        dp.enabled = true
    }

    private fun create(): DynamicsProcessing? = try {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, EqBand.count,
            false, 0,
            false, 0,
            true,
        ).build()
        DynamicsProcessing(0, audioSessionId, config).also { effect = it }
    } catch (_: Throwable) {
        // No such effect on this device (or in a JVM test): play without it rather than fail.
        null
    }

    override fun release() {
        effect?.release()
        effect = null
    }

    companion object {
        val FACTORY = DeckEffectsFactory { DynamicsProcessingEffects(it) }
    }
}
