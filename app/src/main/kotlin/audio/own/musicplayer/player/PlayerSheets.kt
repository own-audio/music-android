@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.SwitchRow
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.music.library.model.Lyrics
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.forView
import audio.own.musicplayer.library.formatDuration
import audio.own.musicplayer.library.rememberCover
import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.musicplayer.ui.Section
import audio.own.player.core.SleepTimer
import audio.own.player.music.CrossfadeRules
import audio.own.player.music.EqBand
import audio.own.player.music.EqPreset
import audio.own.player.music.EqualizerSettings
import audio.own.player.music.MusicPlayer
import audio.own.player.music.ReplayGainMode
import audio.own.player.music.Track
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import audio.own.core.strings.R as SR

/** A player sheet: a title with Done, and its content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheetFrame(title: String, onDismiss: () -> Unit, fullHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)) {
        SheetBody(title, onDismiss, content = content)
    }
}

@Composable
fun SheetBody(title: String, onDone: () -> Unit, leading: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { leading() }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { TextButton(onClick = onDone) { Text(stringResource(SR.string.done)) } }
        }
        content()
    }
}

// Queue

/** Now Playing, Up Next (tap to jump, drag the handle to reorder, swipe to remove) and Played. */
@Composable
fun QueueContent(state: MusicPlayer.State, onJump: (String) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    LazyColumn(modifier.fillMaxWidth(), state = rememberLazyListState()) {
        state.currentTrack?.let { current ->
            item { QueueHeader(stringResource(SR.string.now_playing)) }
            item { QueueRow(current, isCurrent = true) }
        }
        item { QueueHeader(stringResource(R.string.up_next)) }
        if (state.upcoming.isEmpty()) {
            item { Text(stringResource(R.string.nothing_after_this_track), color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.lg)) }
        } else {
            itemsIndexed(state.upcoming, key = { index, track -> "u$index-${track.id}" }) { index, track ->
                UpcomingRow(track, index, state.upcoming.size, onJump = { onJump(track.id) }, onMove = onMove, onRemove = { onRemove(index) })
            }
        }
        if (state.played.isNotEmpty()) {
            item { QueueHeader(stringResource(R.string.played)) }
            itemsIndexed(state.played, key = { index, track -> "p$index-${track.id}" }) { _, track ->
                QueueRow(track, isCurrent = false, modifier = Modifier.alpha(0.6f).clickable { onJump(track.id) })
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LocalPalette.current.muted,
        modifier = Modifier.padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.md, bottom = Tokens.Spacing.xs).semantics { heading() })
}

@Composable
private fun QueueRow(track: Track, isCurrent: Boolean, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        CoverArt(rememberCover(track.artworkId, 44.dp), 44.dp, Icons.Filled.MusicNote, radius = AppBrand.radii.rowCover)
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (isCurrent) palette.accent else palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        track.durationSecs?.let { Text(formatDuration(it.toLong()), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted) }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpcomingRow(track: Track, index: Int, count: Int, onJump: () -> Unit, onMove: (Int, Int) -> Unit, onRemove: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    audio.own.musicplayer.ui.SwipeActions(swipe) { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val palette = LocalPalette.current
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.zIndex(if (offset != 0f) 1f else 0f).graphicsLayer { translationY = offset }
            .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(SR.string.remove), color = Color.White)
            }
        },
    ) {
        QueueRow(track, isCurrent = false, modifier = Modifier.clickable(onClick = onJump)) {
            if (count > 1) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.reorder),
                    tint = palette.muted,
                    modifier = Modifier.size(Tokens.minTarget).padding(12.dp).pointerInput(index, count) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val steps = (offset / rowHeight).roundToInt()
                                val target = (index + steps).coerceIn(0, count - 1)
                                offset = 0f
                                if (target != index) onMove(index, target)
                            },
                            onDragCancel = { offset = 0f },
                        ) { change, dy ->
                            change.consume()
                            offset += dy
                        }
                    },
                )
            }
        }
    }
}

// Sleep timer

@Composable
fun SleepContent(sleep: SleepTimer.State, onPreset: (SleepTimer.Preset) -> Unit, onEndOfTrack: () -> Unit, onExtend: () -> Unit, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = Tokens.Spacing.xl)) {
        val remaining = sleep.remainingSecs
        when {
            remaining != null -> {
                SheetRow(stringResource(R.string.time_remaining), value = formatDuration(remaining.toLong()))
                SheetButton(stringResource(R.string.plus_ten_minutes), onClick = onExtend)
                SheetButton(stringResource(R.string.turn_off_sleep_timer), color = palette.error, onClick = onCancel)
            }
            sleep.stopsAtEndOfItem -> {
                SheetRow(stringResource(R.string.stops_at_end_of_track))
                SheetButton(stringResource(R.string.turn_off_sleep_timer), color = palette.error, onClick = onCancel)
            }
            else -> {
                SleepTimer.Preset.entries.forEach { preset ->
                    SheetButton(pluralStringResource(R.plurals.minutes_count, preset.minutes, preset.minutes), color = palette.foreground) { onPreset(preset) }
                }
                SheetButton(stringResource(R.string.end_of_track), color = palette.foreground, onClick = onEndOfTrack)
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge.tabular(), color = LocalPalette.current.muted) }
    }
}

@Composable
private fun SheetButton(label: String, color: Color = LocalPalette.current.accent, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg, vertical = 14.dp),
    )
}

// Crossfade and volume leveling

@Composable
fun CrossfadeContent(settings: PlaybackSettings, onCrossfade: (Boolean) -> Unit, onFade: (Int) -> Unit, onLeveling: (ReplayGainMode) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl)) {
        CrossfadeSection(settings, onCrossfade, onFade)
        LevelingSection(settings.replayGain, onLeveling)
    }
}

@Composable
fun CrossfadeSection(settings: PlaybackSettings, onCrossfade: (Boolean) -> Unit, onFade: (Int) -> Unit) {
    Section(header = null, footer = stringResource(if (settings.crossfadeEnabled) R.string.crossfade_on_footer else R.string.crossfade_off_footer)) {
        SwitchRow(stringResource(R.string.crossfade), settings.crossfadeEnabled, onCrossfade)
        if (settings.crossfadeEnabled) {
            HorizontalDivider(Modifier.padding(start = Tokens.Spacing.lg), color = LocalPalette.current.border)
            Row(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fade_n_s, settings.fadeSecs), modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { onFade(settings.fadeSecs - 1) }, enabled = settings.fadeSecs > CrossfadeRules.MIN_SECS) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.fade_shorter))
                }
                FilledTonalIconButton(onClick = { onFade(settings.fadeSecs + 1) }, enabled = settings.fadeSecs < CrossfadeRules.MAX_SECS) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.fade_longer))
                }
            }
        }
    }
}

@Composable
fun LevelingSection(mode: ReplayGainMode, onLeveling: (ReplayGainMode) -> Unit) {
    Section(header = stringResource(R.string.volume_leveling), footer = stringResource(R.string.leveling_footer)) {
        listOf(
            ReplayGainMode.OFF to R.string.leveling_off,
            ReplayGainMode.TRACK to R.string.leveling_track,
            ReplayGainMode.ALBUM to R.string.leveling_album,
        ).forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).selectable(selected = mode == value, role = Role.RadioButton) { onLeveling(value) }.padding(horizontal = Tokens.Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                RadioButton(selected = mode == value, onClick = null)
            }
        }
    }
}

// Lyrics

/** Synced lines follow the music, the current one bright and centred, tap to jump; plain lyrics scroll. */
@Composable
fun LyricsContent(state: Load<List<Lyrics>>, progress: () -> Progress, onSeek: (Long) -> Unit, onRetry: () -> Unit, choice: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.TopCenter) {
        LoadStateView(state.forView(), onRetry = onRetry) { all ->
            if (all.isEmpty()) {
                EmptyState(Icons.Filled.FormatQuote, stringResource(R.string.no_lyrics), stringResource(R.string.no_lyrics_text), Modifier.padding(Tokens.Spacing.xl))
            } else {
                LyricsBody(all[choice.coerceIn(0, all.size - 1)], progress, onSeek)
            }
        }
    }
}

@Composable
private fun LyricsBody(lyrics: Lyrics, progress: () -> Progress, onSeek: (Long) -> Unit) {
    var current by remember(lyrics) { mutableStateOf(lyrics.lineIndex(progress().positionMs)) }
    if (lyrics.isSynced) {
        LaunchedEffect(lyrics) {
            while (true) {
                current = lyrics.lineIndex(progress().positionMs)
                delay(250)
            }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(current) { current?.let { list.animateScrollToItem((it - 3).coerceAtLeast(0)) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xl)) {
        itemsIndexed(lyrics.lines) { index, line ->
            val color = when {
                !lyrics.isSynced -> Color.White.copy(alpha = 0.9f)
                index == current -> Color.White
                else -> Color.White.copy(alpha = 0.35f)
            }
            Text(
                line.text.ifEmpty { " " },
                color = color,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.sm)
                    .then(if (line.startMs != null) Modifier.clickable { onSeek(line.startMs!! + lyrics.offsetMs) } else Modifier),
            )
        }
    }
}

/** "Czech · Synced": the language where known, and the kind. */
@Composable
fun lyricsLabel(lyrics: Lyrics): String {
    val language = lyrics.language?.takeIf { it != "xxx" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { name -> name.isNotBlank() } }
    val kind = stringResource(if (lyrics.isSynced) R.string.lyrics_synced else R.string.lyrics_plain)
    return listOfNotNull(language, kind).joinToString(" · ")
}

@Composable
fun LyricsVersionPicker(all: List<Lyrics>, choice: Int, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.lyrics_version)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEachIndexed { index, lyrics ->
                DropdownMenuItem(
                    text = { Text(lyricsLabel(lyrics)) },
                    trailingIcon = { if (index == choice) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onChoose(index) },
                )
            }
        }
    }
}

// Equalizer

val EqPreset.title: Int
    get() = when (name) {
        "Bass Boost" -> R.string.eq_bass_boost
        "Treble Boost" -> R.string.eq_treble_boost
        "Spoken Word" -> R.string.eq_spoken_word
        "Loudness" -> R.string.eq_loudness
        "High Speed 1" -> R.string.eq_high_speed_1
        "High Speed 2" -> R.string.eq_high_speed_2
        else -> R.string.eq_flat
    }

/**
 * The six-band EQ as rows of horizontal sliders, as on iOS: they read as clearly as vertical
 * faders and need no rotation. The sound follows a slider while it moves; the curve is saved
 * when it is let go.
 */
@Composable
fun EqualizerContent(
    eq: EqualizerSettings,
    onEnabled: (Boolean) -> Unit,
    onPreamp: (Double) -> Unit,
    onBand: (Int, Double) -> Unit,
    onRelease: () -> Unit,
    onPreset: (EqPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl)) {
        Section(header = null) { SwitchRow(stringResource(R.string.equalizer), eq.enabled, onEnabled) }
        Section(header = null, footer = stringResource(R.string.equalizer_footer)) {
            GainRow(stringResource(R.string.preamp), eq.preampDb, eq.enabled, onPreamp, onRelease)
            for (index in 0 until EqBand.count) {
                GainRow(EqBand.label(index), eq.bandGainsDb[index], eq.enabled, { onBand(index, it) }, onRelease)
            }
        }
        val header = stringResource(R.string.presets) + if (eq.presetName == null) " · " + stringResource(R.string.custom) else ""
        Section(header = header) {
            FlowRow(
                Modifier.fillMaxWidth().padding(Tokens.Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                maxItemsInEachRow = 2,
            ) {
                EqPreset.all.forEach { preset ->
                    OutlinedButton(onClick = { onPreset(preset) }, enabled = eq.enabled, modifier = Modifier.weight(1f)) {
                        if (eq.presetName == preset.name) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(stringResource(preset.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun GainRow(label: String, value: Double, enabled: Boolean, onChange: (Double) -> Unit, onRelease: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium.tabular(), color = palette.muted, modifier = Modifier.width(64.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            onValueChangeFinished = onRelease,
            valueRange = EqBand.MIN_GAIN_DB.toFloat()..EqBand.MAX_GAIN_DB.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        val rounded = value.roundToInt()
        Text(if (rounded > 0) "+$rounded" else "$rounded", style = MaterialTheme.typography.labelMedium.tabular(), color = palette.muted, modifier = Modifier.width(34.dp))
    }
}
