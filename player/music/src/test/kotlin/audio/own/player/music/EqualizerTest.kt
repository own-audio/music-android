package audio.own.player.music

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Swift suite exists for these; they pin the values and rules ported from music-ios. */
class EqualizerTest {
    @Test
    fun bandsAndLabels() {
        assertEquals(listOf(60.0, 150.0, 400.0, 1_000.0, 2_400.0, 15_000.0), EqBand.standardFrequenciesHz)
        assertEquals(listOf("60", "150", "400", "1K", "2.4K", "15K"), (0 until EqBand.count).map { EqBand.label(it) })
    }

    @Test
    fun presetValuesMatchTheIosApp() {
        val expected = mapOf(
            "Flat" to listOf(0, 0, 0, 0, 0, 0),
            "Bass Boost" to listOf(6, 4, 2, 0, 0, 0),
            "Treble Boost" to listOf(0, 0, 0, 2, 4, 6),
            "Spoken Word" to listOf(-4, -2, 3, 4, 3, -2),
            "Loudness" to listOf(5, 2, 0, 0, 2, 5),
            "High Speed 1" to listOf(0, 0, 1, 2, 3, 2),
            "High Speed 2" to listOf(0, -1, 1, 3, 4, 3),
        )
        assertEquals(expected.keys.toList(), EqPreset.all.map { it.name })
        for (preset in EqPreset.all) {
            assertEquals(preset.name, expected[preset.name]!!.map { it.toDouble() }, preset.bandGainsDb)
        }
    }

    @Test
    fun aSliderMakesTheCurveCustom() {
        val store = EqualizerStore()
        store.applyPreset(EqPreset.named("Bass Boost")!!)
        assertEquals("Bass Boost", store.settings.value.presetName)
        store.setBandGainDb(5, 3.0)
        assertNull("shown as Custom", store.settings.value.presetName)
        assertEquals(listOf(6.0, 4.0, 2.0, 0.0, 0.0, 3.0), store.settings.value.bandGainsDb)
        store.setBandGainDb(9, 1.0)
        assertEquals("an index past the bands is ignored", 6, store.settings.value.bandGainsDb.size)
    }

    @Test
    fun gainsStayInRange() {
        val store = EqualizerStore()
        store.setBandGainDb(0, 30.0)
        store.setPreampDb(-30.0)
        assertEquals(12.0, store.settings.value.bandGainsDb[0], 0.0)
        assertEquals(-12.0, store.settings.value.preampDb, 0.0)
    }

    @Test
    fun savedOnReleaseToggleAndPresetNotOnEveryStep() {
        val persistence = EqualizerStore.InMemoryPersistence()
        val store = EqualizerStore(persistence)
        store.setBandGainDb(1, 2.0)
        assertNull("a drag in progress isn't saved", persistence.load())
        store.save()
        assertEquals(2.0, persistence.load()!!.bandGainsDb[1], 0.0)
        store.setEnabled(true)
        assertTrue(persistence.load()!!.enabled)
        store.applyPreset(EqPreset.named("Loudness")!!)
        assertEquals("Loudness", persistence.load()!!.presetName)

        val relaunched = EqualizerStore(persistence)
        assertEquals(store.settings.value, relaunched.settings.value)
    }

    @Test
    fun resetGoesFlatAndKeepsOnOff() {
        val store = EqualizerStore()
        store.setEnabled(true)
        store.setPreampDb(4.0)
        store.applyPreset(EqPreset.named("Treble Boost")!!)
        store.reset()
        val settings = store.settings.value
        assertTrue(settings.enabled)
        assertEquals(0.0, settings.preampDb, 0.0)
        assertEquals(EqPreset.FLAT, settings.preset)
    }

    @Test
    fun aCurveWithAnotherBandCountIsIgnored() {
        val store = EqualizerStore(EqualizerStore.InMemoryPersistence(EqualizerSettings(enabled = true, bandGainsDb = listOf(1.0, 2.0))))
        assertFalse(store.settings.value.enabled)
        assertEquals(EqBand.count, store.settings.value.bandGainsDb.size)
    }

    @Test
    fun settingsRoundTripAsJson() {
        val settings = EqualizerSettings(enabled = true, preampDb = -2.0, bandGainsDb = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), presetName = null)
        assertEquals(settings, Json.decodeFromString<EqualizerSettings>(Json.encodeToString(settings)))
    }
}
