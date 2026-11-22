package audio.own.musicplayer.library

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.music.library.SmartPlaylistInfo
import audio.own.music.library.SmartPlaylistSource
import audio.own.music.library.SmartPresetInfo
import audio.own.music.library.SmartResult
import audio.own.kit.ownaudio.SmartRule
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import java.text.Collator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The server names its presets in English; the app's own words where it knows the slug. */
object Presets {
    @StringRes
    fun name(slug: String): Int? = when (slug) {
        "forgotten-favourites" -> R.string.preset_forgotten_favourites
        "new-in-the-library" -> R.string.preset_new_in_library
        "back-in-rotation" -> R.string.preset_back_in_rotation
        "what-the-family-plays" -> R.string.preset_family_plays
        "genre-station" -> R.string.preset_genre_station
        else -> null
    }

    @StringRes
    fun summary(slug: String): Int? = when (slug) {
        "forgotten-favourites" -> R.string.preset_forgotten_favourites_text
        "new-in-the-library" -> R.string.preset_new_in_library_text
        "back-in-rotation" -> R.string.preset_back_in_rotation_text
        "what-the-family-plays" -> R.string.preset_family_plays_text
        "genre-station" -> R.string.preset_genre_station_text
        else -> null
    }

    /** A station needs its genre chosen before it can play. */
    fun needsGenre(preset: SmartPresetInfo) = preset.slug == "genre-station"

    /** The preset's rule, narrowed to [genre] where one was chosen. */
    fun rule(preset: SmartPresetInfo, genre: String?): SmartRule =
        if (genre == null) preset.rule else preset.rule.copy(filters = preset.rule.filters.copy(genres = SmartRule.Names(include = listOf(genre.lowercase()))))
}

/** The library's genre names, sorted as a person reads them. */
internal fun sortedGenreNames(names: List<String>): List<String> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    return names.distinct().sortedWith(collator)
}

// A smart playlist's page

data class SmartPageState(
    val target: SmartTarget,
    val result: Load<SmartResult> = Load.Loading,
    /** The genre a Genre Station plays; null until chosen. */
    val genre: String? = null,
    val genres: List<String> = emptyList(),
    val savedAsPlaylist: Boolean = false,
    val deleted: Boolean = false,
) {
    val needsGenre: Boolean get() = target is SmartTarget.Preset && Presets.needsGenre(target.info) && genre == null
}

/**
 * What a smart playlist holds now, a new draw each time it opens or New Selection is tapped
 * (`SmartPlaylistView.swift`), and what can be done with it.
 */
class SmartPlaylistViewModel(
    private val source: SmartPlaylistSource,
    target: SmartTarget,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(SmartPageState(target))
    val state: StateFlow<SmartPageState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (_state.value.needsGenre) loadGenres() else load()
    }

    /** New Selection, and after the rule changed. */
    fun load() {
        val s = _state.value
        if (s.needsGenre) return
        job?.cancel()
        _state.update { it.copy(result = Load.Loading) }
        job = viewModelScope.launch {
            val next = loading {
                when (val t = s.target) {
                    is SmartTarget.Saved -> source.resolveSmartPlaylist(t.info.id)
                    is SmartTarget.Preset -> source.resolve(Presets.rule(t.info, s.genre))
                }
            }
            _state.update { it.copy(result = next) }
        }
    }

    private fun loadGenres() {
        if (_state.value.genres.isNotEmpty()) return
        viewModelScope.launch {
            val names = runCatching { source.genres().map { it.name } }.getOrDefault(emptyList())
            _state.update { it.copy(genres = sortedGenreNames(names)) }
        }
    }

    fun chooseGenre(name: String) {
        _state.update { it.copy(genre = name) }
        load()
    }

    /** The genre button: back to the list of genres. */
    fun clearGenre() {
        job?.cancel()
        _state.update { it.copy(genre = null, result = Load.Loading) }
        loadGenres()
    }

    /** The rule editor saved: the page shows the saved one. */
    fun saved(info: SmartPlaylistInfo) {
        _state.update { it.copy(target = SmartTarget.Saved(info), genre = null) }
        load()
    }

    /** What the rule editor starts from: the saved rule, or a preset to save as one's own. */
    fun draft(presetName: (SmartPresetInfo) -> String): SmartDraft = when (val t = _state.value.target) {
        is SmartTarget.Saved -> SmartDraft(t.info.id, t.info.name, t.info.rule, t.info.isShared)
        is SmartTarget.Preset -> {
            val name = presetName(t.info)
            val genre = _state.value.genre
            SmartDraft(null, if (genre != null) "$name: $genre" else name, Presets.rule(t.info, genre), false)
        }
    }

    fun freeze() {
        val saved = (_state.value.target as? SmartTarget.Saved)?.info ?: return
        perform {
            source.freezeSmartPlaylist(saved.id, null)
            _state.update { it.copy(savedAsPlaylist = true) }
        }
    }

    fun dismissSaved() = _state.update { it.copy(savedAsPlaylist = false) }

    fun delete() {
        val saved = (_state.value.target as? SmartTarget.Saved)?.info ?: return
        perform {
            source.deleteSmartPlaylist(saved.id)
            _state.update { it.copy(deleted = true) }
        }
    }

    private fun perform(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}

// The rule editor

/** What the rule editor starts from; [existingId] is null for a new smart playlist. */
data class SmartDraft(val existingId: String?, val name: String, val rule: SmartRule, val shared: Boolean)

/** The rule's energy is a range from 0 to 1, relative to the library; three bands are what a person chooses between. */
enum class EnergyBand(val range: List<Double>?) {
    ANY(null), CALM(listOf(0.0, 0.4)), MODERATE(listOf(0.3, 0.7)), ENERGETIC(listOf(0.6, 1.0));

    companion object {
        fun of(range: List<Double>?): EnergyBand = entries.firstOrNull { it.range == range } ?: ANY
    }
}

/** The editor's changes to a rule, as the iOS bindings make them. Pure, so they are tested on their own. */
object RuleEdits {
    const val BPM_MIN = 40.0
    const val BPM_MAX = 240.0
    const val BPM_STEP = 5.0
    val COUNT_RANGE = 10..500
    const val COUNT_STEP = 10
    val MINUTES_RANGE = 15..1440
    const val MINUTES_STEP = 15
    val PER_ARTIST_RANGE = 1..20

    /** Ticked genres, lower case; none ticked means all. */
    fun genres(rule: SmartRule, chosen: Set<String>): SmartRule =
        rule.withFilters { copy(genres = if (chosen.isEmpty()) null else SmartRule.Names(include = chosen.map(String::lowercase).sorted())) }

    fun chosenGenres(rule: SmartRule): Set<String> = rule.filters.genres?.include.orEmpty().toSet()

    /** A switch over an optional field: off leaves it out of the rule rather than false. */
    fun favoritesOnly(rule: SmartRule, on: Boolean) = rule.withFilters { copy(starred = if (on) true else null) }

    fun playedByOthers(rule: SmartRule, on: Boolean) = rule.withFilters { copy(playedByOthersNotMe = if (on) true else null) }

    fun minRating(rule: SmartRule, stars: Int?) = rule.withFilters { copy(minRating = stars) }

    fun notPlayedDays(rule: SmartRule, days: Int?) = rule.withFilters { copy(notPlayedDays = days) }

    fun addedDays(rule: SmartRule, days: Int?) = rule.withFilters { copy(addedDays = days) }

    /** Turning the tempo on starts at 90–130 BPM. */
    fun tempo(rule: SmartRule, on: Boolean) = rule.withFilters { copy(bpm = if (on) listOf(90.0, 130.0) else null) }

    /** One end of the tempo range; the other follows so that from never passes to. */
    fun bpmBound(rule: SmartRule, index: Int, value: Double): SmartRule {
        val bpm = rule.filters.bpm?.takeIf { it.size == 2 }?.toMutableList() ?: return rule
        val v = value.coerceIn(BPM_MIN, BPM_MAX)
        bpm[index] = v
        if (bpm[0] > bpm[1]) {
            bpm[0] = v
            bpm[1] = v
        }
        return rule.withFilters { copy(bpm = bpm) }
    }

    fun energy(rule: SmartRule, band: EnergyBand) = rule.withFilters { copy(energy = band.range) }

    /** Songs or minutes; switching starts at 50 songs or an hour. */
    fun limitInMinutes(rule: SmartRule, minutes: Boolean): SmartRule =
        rule.copy(limit = if (minutes) SmartRule.Limit.Minutes(60) else SmartRule.Limit.Count(50))

    fun limitValue(rule: SmartRule, value: Int): SmartRule = rule.copy(
        limit = when (rule.limit) {
            is SmartRule.Limit.Count -> SmartRule.Limit.Count(value.coerceIn(COUNT_RANGE))
            is SmartRule.Limit.Minutes -> SmartRule.Limit.Minutes(value.coerceIn(MINUTES_RANGE))
        },
    )

    /** A stepper's next value: songs by 10, minutes by 15. */
    fun stepLimit(rule: SmartRule, up: Boolean): SmartRule = when (val limit = rule.limit) {
        is SmartRule.Limit.Count -> limitValue(rule, limit.n + if (up) COUNT_STEP else -COUNT_STEP)
        is SmartRule.Limit.Minutes -> limitValue(rule, limit.minutes + if (up) MINUTES_STEP else -MINUTES_STEP)
    }

    fun maxPerArtist(rule: SmartRule, value: Int) = rule.copy(sequence = rule.sequence.copy(maxPerArtist = value.coerceIn(PER_ARTIST_RANGE)))

    fun noConsecutiveArtist(rule: SmartRule, on: Boolean) = rule.copy(sequence = rule.sequence.copy(noConsecutiveArtist = on))

    private inline fun SmartRule.withFilters(change: SmartRule.Filters.() -> SmartRule.Filters) = copy(filters = filters.change())
}

data class RuleEditorState(
    val draft: SmartDraft,
    /** How many songs fit the rule now; null while asking. */
    val matching: Int? = null,
    val genres: List<String> = emptyList(),
    val saving: Boolean = false,
    val error: UiText? = null,
    /** Saved: the editor closes and hands this back. */
    val saved: SmartPlaylistInfo? = null,
) {
    val canSave: Boolean get() = draft.name.isNotBlank() && !saving
}

/**
 * Makes or changes a smart playlist's rule (`SmartRuleEditor.swift`), showing as it goes how many
 * songs fit: asked again once the changes settle for [settleMs], not on every tap of a stepper.
 */
@OptIn(FlowPreview::class)
class SmartRuleEditorViewModel(
    private val source: SmartPlaylistSource,
    draft: SmartDraft,
    private val settleMs: Long = 400,
) : ViewModel() {
    private val _state = MutableStateFlow(RuleEditorState(draft))
    val state: StateFlow<RuleEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val names = runCatching { source.genres().map { it.name } }.getOrDefault(emptyList())
            _state.update { it.copy(genres = sortedGenreNames(names)) }
        }
        var counting: Job? = null
        viewModelScope.launch {
            _state.map { it.draft.rule }.distinctUntilChanged().collect { rule ->
                counting?.cancel()
                _state.update { it.copy(matching = null) }
                counting = launch {
                    delay(settleMs)
                    val count = runCatching { source.resolve(rule).candidates }.getOrNull()
                    if (_state.value.draft.rule == rule) _state.update { it.copy(matching = count) }
                }
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(draft = it.draft.copy(name = name)) }

    fun setShared(shared: Boolean) = _state.update { it.copy(draft = it.draft.copy(shared = shared)) }

    /** Any change to the rule, by one of [RuleEdits]. */
    fun edit(change: (SmartRule) -> SmartRule) = _state.update { it.copy(draft = it.draft.copy(rule = change(it.draft.rule))) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val name = s.draft.name.trim()
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val id = s.draft.existingId
                val saved = if (id != null) {
                    source.updateSmartPlaylist(id, name, s.draft.rule, s.draft.shared)
                } else {
                    source.saveSmartPlaylist(name, s.draft.rule, s.draft.shared)
                }
                _state.update { it.copy(saving = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.userMessage()) }
            }
        }
    }
}
