package audio.own.player.music

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeRulesTest {
    private val ready = CrossfadeRules.Situation(
        fadeSecs = 4.0, isPlaying = true, isFading = false, sleepsAtEndOfTrack = false,
        hasNext = true, nextFullyAvailable = true, durationMs = 200_000, positionMs = 196_000,
    )

    private fun decide(s: CrossfadeRules.Situation) = CrossfadeRules.decide(s, tickMs = 500)

    @Test
    fun `starts when the fade's length is left`() {
        assertEquals(CrossfadeRules.Decision.StartIn(0), decide(ready))
        assertEquals(CrossfadeRules.Decision.StartIn(300), decide(ready.copy(positionMs = 195_700)))
        assertEquals(CrossfadeRules.Decision.No, decide(ready.copy(positionMs = 190_000)))
    }

    @Test
    fun `each guard on its own stops it`() {
        listOf(
            ready.copy(fadeSecs = null),
            ready.copy(isPlaying = false),
            ready.copy(isFading = true),
            ready.copy(sleepsAtEndOfTrack = true),
            ready.copy(hasNext = false),
            ready.copy(nextFullyAvailable = false),
            ready.copy(durationMs = null),
            ready.copy(durationMs = 7_000, positionMs = 3_000),
        ).forEach { assertEquals(it.toString(), CrossfadeRules.Decision.No, decide(it)) }
    }

    @Test
    fun `the ramp`() {
        assertEquals(1f to 0f, CrossfadeRules.ramp(0))
        assertEquals(0.5f to 0.5f, CrossfadeRules.ramp(CrossfadeRules.STEPS / 2))
        assertEquals(0f to 1f, CrossfadeRules.ramp(CrossfadeRules.STEPS))
    }
}
