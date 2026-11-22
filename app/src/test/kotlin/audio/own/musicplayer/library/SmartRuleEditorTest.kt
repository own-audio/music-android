package audio.own.musicplayer.library

import audio.own.kit.ownaudio.SmartRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartRuleEditorTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Test
    fun switchesLeaveTheirFieldOutWhenOff() {
        var rule = SmartRule()
        rule = RuleEdits.favoritesOnly(rule, true)
        assertEquals(true, rule.filters.starred)
        rule = RuleEdits.favoritesOnly(rule, false)
        assertNull(rule.filters.starred)
        rule = RuleEdits.playedByOthers(rule, true)
        assertEquals(true, rule.filters.playedByOthersNotMe)
    }

    @Test
    fun genresAreKeptInLowerCaseAndNoneMeansAll() {
        val rule = RuleEdits.genres(SmartRule(), setOf("Rock", "jazz"))
        assertEquals(listOf("jazz", "rock"), rule.filters.genres?.include)
        assertEquals(setOf("jazz", "rock"), RuleEdits.chosenGenres(rule))
        assertNull(RuleEdits.genres(rule, emptySet()).filters.genres)
    }

    @Test
    fun theTempoStartsAt90To130AndFromNeverPassesTo() {
        var rule = RuleEdits.tempo(SmartRule(), true)
        assertEquals(listOf(90.0, 130.0), rule.filters.bpm)
        rule = RuleEdits.bpmBound(rule, 0, 150.0)
        assertEquals(listOf(150.0, 150.0), rule.filters.bpm)
        rule = RuleEdits.bpmBound(rule, 1, 300.0)
        assertEquals(listOf(150.0, 240.0), rule.filters.bpm)
        assertNull(RuleEdits.tempo(rule, false).filters.bpm)
    }

    @Test
    fun energyBandsAreTheIosRanges() {
        val rule = RuleEdits.energy(SmartRule(), EnergyBand.MODERATE)
        assertEquals(listOf(0.3, 0.7), rule.filters.energy)
        assertEquals(EnergyBand.MODERATE, EnergyBand.of(rule.filters.energy))
        assertEquals(EnergyBand.ANY, EnergyBand.of(listOf(0.1, 0.2)))
        assertNull(RuleEdits.energy(rule, EnergyBand.ANY).filters.energy)
    }

    @Test
    fun theLengthIsSongsOrMinutesWithinTheirRanges() {
        var rule = RuleEdits.limitInMinutes(SmartRule(), true)
        assertEquals(SmartRule.Limit.Minutes(60), rule.limit)
        rule = RuleEdits.stepLimit(rule, up = true)
        assertEquals(SmartRule.Limit.Minutes(75), rule.limit)
        rule = RuleEdits.limitValue(rule, 5)
        assertEquals(SmartRule.Limit.Minutes(15), rule.limit)
        rule = RuleEdits.limitInMinutes(rule, false)
        assertEquals(SmartRule.Limit.Count(50), rule.limit)
        assertEquals(SmartRule.Limit.Count(500), RuleEdits.limitValue(rule, 9_999).limit)
        assertEquals(20, RuleEdits.maxPerArtist(rule, 99).sequence.maxPerArtist)
        assertEquals(1, RuleEdits.maxPerArtist(rule, 0).sequence.maxPerArtist)
    }

    @Test
    fun anEditedRuleGoesOverTheWireAndBackUnchanged() {
        var rule = SmartRule()
        rule = RuleEdits.genres(rule, setOf("jazz"))
        rule = RuleEdits.favoritesOnly(rule, true)
        rule = RuleEdits.minRating(rule, 4)
        rule = RuleEdits.notPlayedDays(rule, 90)
        rule = RuleEdits.addedDays(rule, 30)
        rule = RuleEdits.tempo(rule, true)
        rule = RuleEdits.energy(rule, EnergyBand.CALM)
        rule = RuleEdits.limitInMinutes(rule, true)
        rule = RuleEdits.maxPerArtist(rule, 3)
        rule = RuleEdits.noConsecutiveArtist(rule, false)
        rule = rule.copy(sort = SmartRule.Sort.LEAST_RECENTLY_PLAYED, arc = SmartRule.Arc.WARMUP_SUSTAIN_COOLDOWN)
        val text = json.encodeToString(SmartRule.serializer(), rule)
        val tree = json.parseToJsonElement(text).jsonObject
        assertEquals("duration", tree["limit"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("warmup-sustain-cooldown", tree["arc"]!!.jsonPrimitive.content)
        assertEquals("least-recently-played", tree["sort"]!!.jsonPrimitive.content)
        assertEquals(rule, json.decodeFromString(SmartRule.serializer(), text))
    }

    @Test
    fun theCountIsAskedOnceTheChangesSettle() = runTest(main) {
        val server = FakeSmartServer()
        val vm = SmartRuleEditorViewModel(server, SmartDraft(null, "", SmartRule(), false), settleMs = 400)
        advanceUntilIdle()
        assertEquals(340, vm.state.value.matching)
        assertEquals(listOf("Jazz", "rock"), vm.state.value.genres)
        server.rulesResolved.clear()
        vm.edit { RuleEdits.stepLimit(it, true) }
        advanceTimeBy(100)
        vm.edit { RuleEdits.stepLimit(it, true) }
        advanceTimeBy(100)
        vm.edit { RuleEdits.stepLimit(it, true) }
        assertNull(vm.state.value.matching)
        advanceUntilIdle()
        assertEquals(1, server.rulesResolved.size)
        assertEquals(SmartRule.Limit.Count(80), server.rulesResolved.single().limit)
        assertEquals(340, vm.state.value.matching)
    }

    @Test
    fun saveMakesANewOneOrChangesTheSavedOne() = runTest(main) {
        val server = FakeSmartServer()
        val new = SmartRuleEditorViewModel(server, SmartDraft(null, "", SmartRule(), false))
        assertFalse(new.state.value.canSave)
        new.setName(" Calm ")
        new.setShared(true)
        new.save()
        advanceUntilIdle()
        assertEquals("s|new", new.state.value.saved?.id)
        val edit = SmartRuleEditorViewModel(server, SmartDraft("s|sp1", "Evening", SmartRule(), true))
        assertTrue(edit.state.value.canSave)
        edit.save()
        advanceUntilIdle()
        assertEquals(listOf("save Calm true", "update s|sp1 Evening"), server.log)
    }
}
