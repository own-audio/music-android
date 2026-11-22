package audio.own.musicplayer.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.SwitchRow
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.music.library.SmartResult
import audio.own.kit.ownaudio.SmartRule
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.musicplayer.app.text
import audio.own.musicplayer.ui.BarAction
import audio.own.musicplayer.ui.ErrorLine
import audio.own.musicplayer.ui.NoteRow
import audio.own.musicplayer.ui.Page
import audio.own.musicplayer.ui.RowDivider
import audio.own.musicplayer.ui.Section
import audio.own.core.strings.R as SR

/** "25 songs, 80 min, chosen from 340 that fit". */
@Composable
fun smartSummary(result: SmartResult): String {
    val songs = songsSummary(result.songs)
    return if (result.candidates > result.songs.size) stringResource(R.string.chosen_from_that_fit, songs, result.candidates) else songs
}

/**
 * A smart playlist's page (`SmartPlaylistView.swift`): a new draw each time, New Selection in the
 * bar, and ⋯ Edit Rule / Save as Playlist / Delete on a saved one of the listener's, Save as
 * Smart Playlist on a preset.
 */
@Composable
fun SmartPlaylistContent(
    state: SmartPageState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onGenre: (String) -> Unit,
    onClearGenre: () -> Unit,
    onEditRule: () -> Unit,
    onFreeze: () -> Unit,
    onDelete: () -> Unit,
    onSaveAsSmart: () -> Unit,
    onDismissSaved: () -> Unit,
) {
    val ui = LocalLibraryUi.current
    val palette = LocalPalette.current
    val target = state.target
    val saved = (target as? SmartTarget.Saved)?.info
    val preset = (target as? SmartTarget.Preset)?.info
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val name = target.displayName()
    val container = when (target) {
        is SmartTarget.Saved -> "smart:${target.info.id}"
        is SmartTarget.Preset -> "smart-preset:${target.info.slug}"
    }
    val result = (state.result as? Load.Loaded)?.value
    ListPage(
        title = name,
        onBack = onBack,
        actions = {
            IconButton(onClick = onReload, enabled = !state.needsGenre) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.new_selection)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.smart_playlist_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (saved != null) {
                        if (saved.isMine) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit_rule)) }, leadingIcon = { Icon(Icons.Filled.Tune, null) }, onClick = { menu = false; onEditRule() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as_playlist)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, onClick = { menu = false; onFreeze() })
                        if (saved.isMine) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_smart_playlist), color = palette.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = palette.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    } else if (preset != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as_smart_playlist)) }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) }, onClick = { menu = false; onSaveAsSmart() })
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    val explanation = preset?.summaryText()
                        ?: stringResource(if (saved?.isShared == true) R.string.smart_explanation_shared else R.string.smart_explanation)
                    SmartHeader(name, explanation)
                    state.genre?.let { genre -> OutlinedButton(onClick = onClearGenre) { Text(genre) } }
                    val songs = result?.songs.orEmpty()
                    PlayShuffleButtons(songs.isNotEmpty(), { ui.play(songs, null, false, container) }, { ui.play(songs, null, true, container) })
                }
            }
            if (state.needsGenre) {
                item { Heading(stringResource(R.string.choose_a_genre), Modifier.padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.md)) }
                if (state.genres.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                items(state.genres, key = { it }) { genre ->
                    Text(
                        genre,
                        color = palette.accent,
                        modifier = Modifier.fillMaxWidth().clickable { onGenre(genre) }.heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.md),
                    )
                }
            } else {
                when (val r = state.result) {
                    is Load.Loaded -> {
                        if (r.value.songs.isEmpty()) {
                            item { EmptyState(Icons.Filled.AutoAwesome, stringResource(R.string.nothing_fits), stringResource(R.string.nothing_fits_text), Modifier.heightIn(min = 240.dp)) }
                        } else {
                            itemsIndexed(r.value.songs, key = { index, song -> "$index-${song.id}" }) { _, song ->
                                SongRow(song, onClick = { ui.play(r.value.songs, song.id, false, container) }, showsCover = true)
                            }
                            item { Text(smartSummary(r.value), style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.screenEdge)) }
                        }
                    }
                    else -> item {
                        Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) { LoadStateView(r.forView(), onRetry = onReload) {} }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named_question, name)) },
            text = { Text(stringResource(if (saved?.isShared == true) R.string.delete_smart_shared_message else R.string.delete_smart_message)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete_smart_playlist), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
    if (state.savedAsPlaylist) {
        AlertDialog(
            onDismissRequest = onDismissSaved,
            title = { Text(stringResource(R.string.saved_as_playlist)) },
            text = { Text(stringResource(R.string.saved_as_playlist_text)) },
            confirmButton = { TextButton(onClick = onDismissSaved) { Text(stringResource(SR.string.ok)) } },
        )
    }
}

/** A choice among a few values, as the iOS pickers in a form: a row that opens its options. */
@Composable
private fun <T> PickerRow(label: String, value: T, options: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueRow(label, options.firstOrNull { it.first == value }?.second, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    trailingIcon = { if (option == value) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onChoose(option) },
                )
            }
        }
    }
}

/** − value + as one row, with the value read out as the label. */
@Composable
private fun StepperRow(label: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(start = Tokens.Spacing.lg, end = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onDown, enabled = canDown) { Text("−", style = MaterialTheme.typography.titleLarge) }
        TextButton(onClick = onUp, enabled = canUp) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * The rule editor (`SmartRuleEditor.swift`): name and sharing, the live count, then what the
 * songs are, their tempo and energy, the length and the order.
 */
@Composable
fun SmartRuleEditorContent(
    state: RuleEditorState,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onName: (String) -> Unit,
    onShared: (Boolean) -> Unit,
    onEdit: ((SmartRule) -> SmartRule) -> Unit,
) {
    var choosingGenres by remember { mutableStateOf(false) }
    val rule = state.draft.rule
    val filters = rule.filters
    if (choosingGenres) {
        GenreChoice(state.genres, RuleEdits.chosenGenres(rule), onBack = { choosingGenres = false }) { chosen -> onEdit { RuleEdits.genres(it, chosen) } }
        return
    }
    Page(
        title = stringResource(if (state.draft.existingId == null) R.string.new_smart_playlist else R.string.edit_smart_playlist),
        onBack = onCancel,
        closeIsCancel = true,
        actions = { BarAction(stringResource(SR.string.save), enabled = state.canSave, working = state.saving, onClick = onSave) },
    ) {
        Section(header = null, footer = stringResource(R.string.smart_share_footer)) {
            OutlinedTextField(
                value = state.draft.name,
                onValueChange = onName,
                label = { Text(stringResource(SR.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
            )
            RowDivider()
            SwitchRow(stringResource(R.string.share_with_the_family), state.draft.shared, onShared)
        }
        Section(header = null) {
            val matching = state.matching
            if (matching != null) {
                NoteRow(pluralStringResource(R.plurals.songs_fit, matching, matching), icon = Icons.Filled.MusicNote, color = LocalPalette.current.foreground)
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(Tokens.Spacing.sm)) }
            }
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }

        Section(header = stringResource(R.string.songs)) {
            val chosen = RuleEdits.chosenGenres(rule)
            val genreSummary = when (chosen.size) {
                0 -> stringResource(R.string.all)
                1 -> chosen.first().replaceFirstChar { it.titlecase() }
                else -> pluralStringResource(R.plurals.genres_count, chosen.size, chosen.size)
            }
            ValueRow(stringResource(R.string.genres), genreSummary, onClick = { choosingGenres = true })
            RowDivider()
            SwitchRow(stringResource(R.string.favorites_only), filters.starred == true, onCheckedChange = { on -> onEdit { RuleEdits.favoritesOnly(it, on) } })
            RowDivider()
            PickerRow(
                stringResource(R.string.rating_at_least),
                filters.minRating,
                listOf<Pair<Int?, String>>(null to stringResource(R.string.any)) + (1..5).map { it to "★".repeat(it) },
            ) { stars -> onEdit { RuleEdits.minRating(it, stars) } }
            RowDivider()
            PickerRow(
                stringResource(R.string.not_played_for),
                filters.notPlayedDays,
                listOf(
                    null to stringResource(R.string.any_time),
                    30 to stringResource(R.string.a_month),
                    90 to stringResource(R.string.three_months),
                    180 to stringResource(R.string.half_a_year),
                    365 to stringResource(R.string.a_year),
                ),
            ) { days -> onEdit { RuleEdits.notPlayedDays(it, days) } }
            RowDivider()
            PickerRow(
                stringResource(R.string.added_in_the_last),
                filters.addedDays,
                listOf(
                    null to stringResource(R.string.any_time),
                    7 to stringResource(R.string.week),
                    30 to stringResource(R.string.month),
                    90 to stringResource(R.string.three_months),
                ),
            ) { days -> onEdit { RuleEdits.addedDays(it, days) } }
            RowDivider()
            SwitchRow(stringResource(R.string.played_by_others), filters.playedByOthersNotMe == true, onCheckedChange = { on -> onEdit { RuleEdits.playedByOthers(it, on) } })
        }

        Section(header = stringResource(R.string.tempo_and_energy), footer = stringResource(R.string.tempo_footer)) {
            SwitchRow(stringResource(R.string.limit_tempo), filters.bpm != null, onCheckedChange = { on -> onEdit { RuleEdits.tempo(it, on) } })
            val bpm = filters.bpm?.takeIf { it.size == 2 }
            if (bpm != null) {
                RowDivider()
                StepperRow(
                    stringResource(R.string.from_bpm, bpm[0].toInt()),
                    canDown = bpm[0] > RuleEdits.BPM_MIN, canUp = bpm[0] < RuleEdits.BPM_MAX,
                    onDown = { onEdit { RuleEdits.bpmBound(it, 0, bpm[0] - RuleEdits.BPM_STEP) } },
                    onUp = { onEdit { RuleEdits.bpmBound(it, 0, bpm[0] + RuleEdits.BPM_STEP) } },
                )
                RowDivider()
                StepperRow(
                    stringResource(R.string.to_bpm, bpm[1].toInt()),
                    canDown = bpm[1] > RuleEdits.BPM_MIN, canUp = bpm[1] < RuleEdits.BPM_MAX,
                    onDown = { onEdit { RuleEdits.bpmBound(it, 1, bpm[1] - RuleEdits.BPM_STEP) } },
                    onUp = { onEdit { RuleEdits.bpmBound(it, 1, bpm[1] + RuleEdits.BPM_STEP) } },
                )
            }
            RowDivider()
            PickerRow(
                stringResource(R.string.energy),
                EnergyBand.of(filters.energy),
                listOf(
                    EnergyBand.ANY to stringResource(R.string.any),
                    EnergyBand.CALM to stringResource(R.string.energy_calm),
                    EnergyBand.MODERATE to stringResource(R.string.energy_moderate),
                    EnergyBand.ENERGETIC to stringResource(R.string.energy_energetic),
                ),
            ) { band -> onEdit { RuleEdits.energy(it, band) } }
        }

        Section(header = stringResource(R.string.length)) {
            val minutes = rule.limit is SmartRule.Limit.Minutes
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)) {
                SegmentedButton(selected = !minutes, onClick = { onEdit { RuleEdits.limitInMinutes(it, false) } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.songs))
                }
                SegmentedButton(selected = minutes, onClick = { onEdit { RuleEdits.limitInMinutes(it, true) } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.minutes))
                }
            }
            RowDivider()
            when (val limit = rule.limit) {
                is SmartRule.Limit.Count -> StepperRow(
                    pluralStringResource(R.plurals.songs_count, limit.n, limit.n),
                    canDown = limit.n > RuleEdits.COUNT_RANGE.first, canUp = limit.n < RuleEdits.COUNT_RANGE.last,
                    onDown = { onEdit { RuleEdits.stepLimit(it, up = false) } }, onUp = { onEdit { RuleEdits.stepLimit(it, up = true) } },
                )
                is SmartRule.Limit.Minutes -> StepperRow(
                    stringResource(R.string.n_min, limit.minutes),
                    canDown = limit.minutes > RuleEdits.MINUTES_RANGE.first, canUp = limit.minutes < RuleEdits.MINUTES_RANGE.last,
                    onDown = { onEdit { RuleEdits.stepLimit(it, up = false) } }, onUp = { onEdit { RuleEdits.stepLimit(it, up = true) } },
                )
            }
        }

        Section(header = stringResource(R.string.order)) {
            PickerRow(
                stringResource(R.string.order),
                rule.sort,
                listOf(
                    SmartRule.Sort.WEIGHTED_RANDOM to stringResource(R.string.order_weighted),
                    SmartRule.Sort.WEIGHT to stringResource(R.string.order_best),
                    SmartRule.Sort.LEAST_RECENTLY_PLAYED to stringResource(R.string.order_unplayed),
                    SmartRule.Sort.RANDOM to stringResource(R.string.order_random),
                ),
            ) { sort -> onEdit { it.copy(sort = sort) } }
            RowDivider()
            PickerRow(
                stringResource(R.string.energy_over_time),
                rule.arc,
                listOf(
                    SmartRule.Arc.NONE to stringResource(R.string.arc_even),
                    SmartRule.Arc.WARMUP_SUSTAIN_COOLDOWN to stringResource(R.string.arc_warmup),
                    SmartRule.Arc.BUILD to stringResource(R.string.arc_build),
                    SmartRule.Arc.UNWIND to stringResource(R.string.arc_unwind),
                ),
            ) { arc -> onEdit { it.copy(arc = arc) } }
            RowDivider()
            val perArtist = rule.sequence.maxPerArtist
            StepperRow(
                stringResource(R.string.at_most_per_artist, perArtist),
                canDown = perArtist > RuleEdits.PER_ARTIST_RANGE.first, canUp = perArtist < RuleEdits.PER_ARTIST_RANGE.last,
                onDown = { onEdit { RuleEdits.maxPerArtist(it, perArtist - 1) } }, onUp = { onEdit { RuleEdits.maxPerArtist(it, perArtist + 1) } },
            )
            RowDivider()
            SwitchRow(stringResource(R.string.no_artist_twice), rule.sequence.noConsecutiveArtist, onCheckedChange = { on -> onEdit { RuleEdits.noConsecutiveArtist(it, on) } })
        }
    }
}

/** The library's genres to tick; none ticked means all. */
@Composable
private fun GenreChoice(all: List<String>, chosen: Set<String>, onBack: () -> Unit, onChange: (Set<String>) -> Unit) {
    val palette = LocalPalette.current
    ListPage(
        title = stringResource(R.string.genres),
        onBack = onBack,
        actions = { if (chosen.isNotEmpty()) TextButton(onClick = { onChange(emptySet()) }) { Text(stringResource(R.string.all)) } },
    ) { inner ->
        if (all.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ListPage
        }
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            items(all, key = { it }) { name ->
                val key = name.lowercase()
                val on = key in chosen
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = on, role = Role.Checkbox) { onChange(if (on) chosen - key else chosen + key) }
                        .heightIn(min = Tokens.minTarget)
                        .padding(horizontal = Tokens.Spacing.screenEdge),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected), tint = palette.accent)
                }
            }
        }
    }
}
