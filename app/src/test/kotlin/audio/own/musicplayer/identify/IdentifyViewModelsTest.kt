package audio.own.musicplayer.identify

import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.music.library.IdentifyAvailability
import audio.own.music.library.identify.IdentifyRunReview
import audio.own.music.library.identify.IdentifyRuns
import audio.own.music.library.identify.IdentifyTab
import audio.own.music.library.identify.RecordingResults
import audio.own.musicplayer.FakeOwnAudio
import audio.own.musicplayer.ModelHarness
import audio.own.musicplayer.FakeOwnAudio.Companion.json
import audio.own.musicplayer.FakeOwnAudio.Companion.problem
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.eventually
import audio.own.musicplayer.mainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentifyViewModelsTest {
    private val main = mainThread()
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url, demo = fake.url)
    private lateinit var client: OwnAudioClient

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        harness.model.signIn(fake.url, "a@example.com", fake.password, "")
        client = harness.model.client(harness.store.loadAll().single().id)!!
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        fake.close()
        harness.close()
    }

    private fun route(answer: (method: String, path: String) -> MockResponse?) {
        fake.extra = { answer(it.method, it.url.encodedPath) }
    }

    private fun posts() = fake.paths().filter { it.startsWith("POST") || it.startsWith("PUT") || it.startsWith("DELETE") }

    private fun runs(charges: Boolean) = IdentifyRuns(client, IdentifyAvailability(charges), signals = { emptyFlow() })

    private fun review() = IdentifyRunReview(client, "r1", signals = { emptyFlow() })

    @Test
    fun theRunsLoadAndAChargingServerAsksBeforeStarting() = runBlocking {
        var credit = true
        route { method, path ->
            when ("$method $path") {
                "GET /api/v2/identify/runs" -> json("""{"items":[${run("ready")}]}""")
                "GET /api/v2/library/folders" -> json("""{"folders":[{"id":"f1","path":"/music","media":"music"},{"id":"f2","path":"/books","media":"audiobooks"}]}""")
                "POST /api/v2/identify/quote" -> json("""{"tracks":12,"charge_micro":12000}""")
                "POST /api/v2/identify/runs" -> if (credit) json(run("queued"), 202) else problem("payment_required", 402)
                else -> null
            }
        }
        val vm = IdentifyRunsViewModel(runs(charges = true), "Whole library")
        eventually { vm.state.value.runs is Load.Loaded && vm.state.value.folders.isNotEmpty() }
        assertEquals(listOf("r1"), (vm.state.value.runs as Load.Loaded).value.map { it.id })
        assertEquals("only music folders", listOf("/music"), vm.state.value.folders.map { it.path })

        vm.choose(vm.state.value.folders.single())
        vm.start()
        eventually { vm.state.value.confirm != null }
        assertEquals(12_000L, vm.state.value.confirm?.quote?.chargeMicro)
        assertTrue("nothing started before Start", posts().none { it == "POST /api/v2/identify/runs" })

        vm.confirmStart()
        eventually { vm.state.value.opened != null }
        assertEquals("r1", vm.state.value.opened)
        assertTrue(fake.requests.single { it.method == "POST" && it.url.encodedPath == "/api/v2/identify/runs" }.body!!.utf8().contains("\"kind\":\"folder\""))

        vm.openedShown()
        credit = false
        vm.choose(null)
        vm.start()
        eventually { vm.state.value.confirm != null }
        vm.confirmStart()
        eventually { vm.state.value.startError != null }
        assertEquals(UiText(R.string.identify_no_credit), vm.state.value.startError)
        assertNull(vm.state.value.opened)
    }

    @Test
    fun aFreeServerStartsAtOnceAndAFailedFirstLoadSaysWhy() = runBlocking {
        var down = true
        route { method, path ->
            when ("$method $path") {
                "GET /api/v2/identify/runs" -> if (down) problem("internal", 500) else json("""{"items":[]}""")
                "POST /api/v2/identify/runs" -> json(run("queued"), 202)
                else -> null
            }
        }
        val vm = IdentifyRunsViewModel(runs(charges = false), "Whole library")
        eventually { vm.state.value.runs is Load.Failed }
        down = false
        vm.retry()
        eventually { vm.state.value.runs is Load.Loaded }
        vm.start()
        eventually { vm.state.value.opened != null }
        assertTrue(posts().none { it.endsWith("/quote") })
    }

    @Test
    fun aRunsActionsLeaveTheirNotesAndTheCatalogueCatchesUp() = runBlocking {
        var deleted = false
        route { method, path ->
            when ("$method $path") {
                "GET /api/v2/identify/runs/r1" -> if (deleted) problem("not_found", 404) else json(run("ready"))
                "GET /api/v2/identify/runs/r1/proposals" -> json("""{"items":[${proposal("1", "accepted")},${proposal("2", "pending")}]}""")
                "POST /api/v2/identify/runs/r1/accept" -> json("""{"accepted":7}""")
                "DELETE /api/v2/tracks/t1/identity" -> problem("not_identified", 409)
                "POST /api/v2/identify/runs/r1/revert" -> json("""{"reverted":3}""")
                "DELETE /api/v2/identify/runs/r1" -> MockResponse.Builder().code(204).build().also { deleted = true }
                else -> null
            }
        }
        var changed = 0
        val vm = IdentifyRunViewModel(review()) { changed++ }
        eventually { vm.state.value.review.run != null && !vm.state.value.review.isLoading }
        assertEquals(listOf("A"), vm.state.value.groups.map { it.group.id })

        vm.show(IdentifyTab.SURE)
        eventually { vm.state.value.review.tab == IdentifyTab.SURE && !vm.state.value.review.isLoading }
        vm.acceptSure()
        eventually { vm.state.value.message != null && !vm.state.value.busy }
        assertEquals(RunNote.Identified(7), vm.state.value.message)
        assertEquals(1, changed)

        vm.messageShown()
        eventually { vm.state.value.message == null }
        vm.undo(vm.state.value.review.proposals.first { it.id == "1" })
        eventually { vm.state.value.message != null && !vm.state.value.busy }
        assertEquals("409: it never was", RunNote.NeverIdentified, vm.state.value.message)

        vm.messageShown()
        eventually { vm.state.value.message == null }
        vm.revert()
        eventually { vm.state.value.message != null && !vm.state.value.busy }
        assertEquals(RunNote.PutBack(3), vm.state.value.message)

        vm.delete()
        eventually { vm.state.value.review.isGone }
    }

    @Test
    fun theRecordingPickerStartsFromTheTagsWidensAndClosesOnceChosen() = runBlocking {
        val asked = mutableListOf<String>()
        var chosen: Pair<String, String?>? = null
        val vm = RecordingPickerViewModel(
            "Track 02", "Band", "Wrong Album",
            search = { t, a, al ->
                asked += "$t|$a|$al"
                RecordingResults(listOf(candidate()), widenedFrom = "Wrong Album")
            },
            choose = { recording, release -> chosen = recording to release },
        )
        assertEquals("Track 02", vm.state.value.title)
        vm.setTitle("Real Song")
        vm.runSearch()
        eventually { vm.state.value.searched }
        assertEquals(listOf("Real Song|Band|Wrong Album"), asked)
        assertEquals("Wrong Album", vm.state.value.widenedFrom)
        vm.pick(vm.state.value.results.single())
        eventually { vm.state.value.done }
        assertEquals("m1" to "rel1", chosen)

        val pasted = RecordingPickerViewModel("A", null, null, search = { _, _, _ -> RecordingResults(emptyList()) }, choose = { r, _ -> chosen = r to null })
        pasted.setLink("  https://musicbrainz.org/recording/abc  ")
        pasted.useLink()
        eventually { pasted.state.value.done }
        assertEquals("https://musicbrainz.org/recording/abc" to null, chosen)

        val blank = RecordingPickerViewModel("", null, null, search = { _, _, _ -> error("not asked") }, choose = { _, _ -> })
        blank.runSearch()
        assertEquals(false, blank.state.value.searching)
    }

    @Test
    fun anEditionIsChosenAndTheRunReloads() = runBlocking {
        route { method, path ->
            when ("$method $path") {
                "GET /api/v2/identify/groups/g1" -> json(GROUP)
                "PUT /api/v2/identify/groups/g1/edition" -> json(GROUP.replace("\"chosen\":0", "\"chosen\":1"))
                "GET /api/v2/identify/runs/r1" -> json(run("ready"))
                "GET /api/v2/identify/runs/r1/proposals" -> json("""{"items":[]}""")
                else -> null
            }
        }
        var changed = 0
        val vm = EditionsViewModel(review(), "g1", "Album", "Band") { changed++ }
        eventually { vm.state.value.group is Load.Loaded }
        assertEquals(2, (vm.state.value.group as Load.Loaded).value.editions.size)
        vm.choose(1)
        eventually { vm.state.value.done }
        assertEquals(1, changed)
        assertTrue(posts().contains("PUT /api/v2/identify/groups/g1/edition"))
    }

    private fun run(status: String) =
        """{"id":"r1","status":"$status","scope":{"kind":"library","label":"Whole library"},"tracks":3,"groups_total":2,"groups_done":1,"counts":{"sure":1,"review":2,"accepted":1},"created_at":"2026-10-12T05:01:38Z"}"""

    private fun proposal(id: String, decision: String) =
        """{"id":"$id","group":{"id":"A","kind":"album","label":"Album A"},
        "track":{"id":"t$id","title":"Song $id","artist":"Band","album":"Album A","disc_number":1,"track_number":$id,"duration_secs":200},
        "class":"sure","reasons":[],
        "proposal":{"mb_recording_id":"m$id","title":"Song $id","album":"Album A","mb_release_id":"relA","score":90},
        "decision":"$decision"}"""

    private fun candidate() = audio.own.kit.ownaudio.RecordingCandidate("m1", "Real Song", artist = "Band", album = "Other", mbReleaseId = "rel1", durationMs = 200_000)

    private companion object {
        const val GROUP = """{"id":"g1","kind":"album","label":"Album A","chosen":0,"editions":[
            {"index":0,"mb_release_id":"r0","title":"Album A","year":2001,"track_count":10,"matched":9},
            {"index":1,"mb_release_id":"r1","title":"Album A (Deluxe)","year":2011,"track_count":14,"matched":10,"secondary_types":["Compilation"]}]}"""
    }
}
