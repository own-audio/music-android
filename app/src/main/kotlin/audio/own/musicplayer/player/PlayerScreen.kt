@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.player

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import audio.own.musicplayer.ui.asHeading
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.LocalReduceMotion
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.musicplayer.R
import audio.own.musicplayer.library.formatDuration
import audio.own.musicplayer.library.rememberCover
import audio.own.player.core.SleepTimer
import audio.own.player.music.MusicPlayer
import audio.own.player.music.RepeatMode
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import audio.own.core.strings.R as SR

/** What the player screen shows besides the engine's state. */
data class PlayerUi(
    val state: MusicPlayer.State = MusicPlayer.State(),
    val sleep: SleepTimer.State = SleepTimer.State(),
    val error: String? = null,
    val isFavorite: Boolean = false,
    val crossfadeOn: Boolean = false,
    val equalizerOn: Boolean = false,
    /** The song playing can be identified (own.audio, the listener's own): the Identify tool. */
    val canIdentify: Boolean = false,
)

enum class PlayerSheet { QUEUE, SLEEP, CROSSFADE, LYRICS, EQUALIZER, CHAPTERS }

/**
 * The full player, always dark: the cover blurred behind (Android 12+; a dimmed cover below),
 * the cover, title with the star, artist · album, the format badge, "Track 1 of 5", the scrubber,
 * the transport, and the tools row. Swipe the cover sideways for next and previous.
 */
@Composable
fun PlayerContent(
    ui: PlayerUi,
    progress: () -> Progress,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSheet: (PlayerSheet) -> Unit,
    tickMs: Long = 250,
    onIdentify: () -> Unit = {},
    onUndoIdentify: () -> Unit = {},
) {
    val track = ui.state.currentTrack
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A keyboard on a tablet or a Chromebook: Space plays and pauses, Ctrl+← and Ctrl+→
            // go to the previous and next song, Esc closes the player. A sheet over it takes
            // the keys itself.
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && playerKey(event, track != null, ui.state.hasNext, onToggle, onNext, onPrevious, onClose) },
    ) {
        Crossfade(track?.artworkId, animationSpec = tween(AppBrand.COVER_CROSSFADE_MS), label = "background") { artwork ->
            if (artwork != null) {
                val blurred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                AsyncImage(
                    model = rememberCover(artwork, 300.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (blurred) Modifier.blur(60.dp) else Modifier.alpha(0.6f)),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.close_player), tint = Color.White) }
                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onStop() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop_playback), tint = Color.White)
                }
            }
            // The width cap first: fillMaxSize before it would stretch the column over a tablet.
            BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val column = min(maxWidth, 600.dp)
                // A tablet in landscape is short: the cover gives way so the controls stay on screen.
                val coverSide = minOf(maxOf(column - 120.dp, 280.dp), 400.dp, column - Tokens.Spacing.screenEdge * 2, maxOf(maxHeight - 380.dp, 160.dp))
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
                ) {
                    Spacer(Modifier.weight(1f))
                    Cover(ui, coverSide, onNext, onPrevious)
                    Titles(ui, onFavorite, onChapters = { onSheet(PlayerSheet.CHAPTERS) })
                    Scrubber(progress, onSeek, tickMs, Modifier.padding(horizontal = Tokens.Spacing.screenEdge))
                    Transport(ui, progress, onToggle, onNext, onPrevious)
                    ui.error?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Spacer(Modifier.weight(1f))
                    Tools(ui, onShuffle, onRepeat, onSheet, onIdentify, onUndoIdentify)
                    Spacer(Modifier.height(Tokens.Spacing.md))
                }
            }
        }
    }
}

@Composable
private fun Cover(ui: PlayerUi, side: androidx.compose.ui.unit.Dp, onNext: () -> Unit, onPrevious: () -> Unit) {
    val track = ui.state.currentTrack
    val haptics = LocalHapticFeedback.current
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .sizeIn(maxWidth = side, maxHeight = side)
            .aspectRatio(1f)
            .pointerInput(ui.state.hasNext, ui.state.hasPrevious) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        if (drag < -threshold && ui.state.hasNext) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() }
                        if (drag > threshold && ui.state.hasPrevious) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() }
                        drag = 0f
                    },
                ) { _, delta -> drag += delta }
            },
    ) {
        Crossfade(track?.artworkId, animationSpec = tween(AppBrand.COVER_CROSSFADE_MS), label = "cover") { artwork ->
            CoverArt(rememberCover(artwork, side), side, Icons.Filled.MusicNote, radius = AppBrand.radii.cover)
        }
        if (ui.sleep.isActive) {
            val text = ui.sleep.remainingSecs?.let { formatDuration(it.toLong()) } ?: stringResource(R.string.end_of_track_badge)
            Row(
                Modifier.align(Alignment.TopEnd).padding(Tokens.Spacing.sm).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = Tokens.Spacing.sm, vertical = Tokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium.tabular())
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Titles(ui: PlayerUi, onFavorite: () -> Unit, onChapters: () -> Unit) {
    val state = ui.state
    val track = state.currentTrack
    val chapter = state.chapterIndex?.let { state.chapters.getOrNull(it) }
    val still = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                chapter?.title ?: track?.title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Tokens.minTarget).asHeading()
                    .then(if (still) Modifier else Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2_000)),
            )
            if (track != null) {
                IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(
                        if (ui.isFavorite) Icons.Filled.Star else Icons.Filled.StarOutline,
                        contentDescription = stringResource(if (ui.isFavorite) R.string.unfavorite else R.string.favorite),
                        tint = if (ui.isFavorite) Color(0xFFFFCC00) else Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
        val second = listOfNotNull(track?.artist, if (chapter != null) track?.title else track?.album).joinToString(" · ")
        Text(second, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.format?.label(state.replayGainDb)?.let { badge ->
            val label = stringResource(R.string.format_badge, badge)
            Text(badge, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 2.dp).semantics { contentDescription = label })
        }
        val chapterIndex = state.chapterIndex
        if (chapterIndex != null) {
            // The concert's songs: tap for the list.
            TextButton(
                onClick = onChapters,
                modifier = Modifier.padding(top = 2.dp).heightIn(min = Tokens.minTarget),
            ) {
                Row(
                    Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape).padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.song_n_of_m, chapterIndex + 1, state.chapters.size),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        } else {
            state.position?.let { Text(stringResource(R.string.track_n_of_m, it.current, it.total), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * The position and times, redrawn on their own about four times a second; a drag holds its
 * target until the seek lands, so the thumb doesn't jump back meanwhile. Drawn as music-ios's
 * `PlaybackScrubber`: a thin white line, the buffered part lighter, a small round thumb, in
 * place of Material's thick track with its gap and end dot. TalkBack hears "1:23 of 4:56" and
 * moves it in steps with the volume keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scrubber(progress: () -> Progress, onSeek: (Long) -> Unit, tickMs: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(progress()) }
    var target by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = progress()
            delay(tickMs)
        }
    }
    val duration = now.durationMs.coerceAtLeast(1)
    val fraction = target ?: (now.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownMs = (fraction * duration).toLong()
    val position = stringResource(R.string.playback_position)
    val value = stringResource(R.string.time_of_duration, formatDuration(now.positionMs.coerceAtLeast(0) / 1000), formatDuration(duration / 1000))
    val buffered = (now.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    val interaction = remember { MutableInteractionSource() }
    // The state overload is the one that takes a thumb and a track of our own.
    val slider = remember { SliderState() }
    LaunchedEffect(fraction) { if (target == null) slider.value = fraction }
    Column(modifier.fillMaxWidth()) {
        Slider(
            state = slider,
            onValueChange = {
                slider.value = it
                target = it
            },
            onValueChangeFinished = {
                target?.let { onSeek((it * duration).toLong()) }
                target = null
            },
            interactionSource = interaction,
            thumb = { ScrubberThumb() },
            track = { state -> ScrubberTrack(state.value, buffered) },
            modifier = Modifier.fillMaxWidth().height(Tokens.minTarget).semantics {
                contentDescription = position
                stateDescription = value
            },
        )
        // The times line up with the ends of the line, which starts half a thumb in.
        Row(Modifier.fillMaxWidth().padding(horizontal = SCRUBBER_THUMB / 2), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shownMs / 1000), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium.tabular())
            Text("-" + formatDuration((duration - shownMs).coerceAtLeast(0) / 1000), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium.tabular())
        }
    }
}

/** The scrubber's thumb: a 14 dp white dot. */
@Composable
private fun ScrubberThumb() {
    Box(Modifier.size(SCRUBBER_THUMB).background(Color.White, CircleShape))
}

/** The scrubber's line, 4 dp: what is left, what has arrived (lighter), what has played (white). */
@Composable
private fun ScrubberTrack(played: Float, buffered: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
        if (buffered > played) Box(Modifier.fillMaxWidth(buffered).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
        Box(Modifier.fillMaxWidth(played.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
    }
}

private val SCRUBBER_THUMB = 14.dp

/** The player's keys; true when [event] was one of them. */
internal fun playerKey(
    event: KeyEvent,
    hasTrack: Boolean,
    hasNext: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
): Boolean = when {
    event.key == Key.Escape -> { onClose(); true }
    !hasTrack -> false
    event.key == Key.Spacebar && !event.isCtrlPressed -> { onToggle(); true }
    event.key == Key.DirectionRight && event.isCtrlPressed -> { if (hasNext) onNext(); true }
    event.key == Key.DirectionLeft && event.isCtrlPressed -> { onPrevious(); true }
    else -> false
}

@Composable
private fun Transport(ui: PlayerUi, progress: () -> Progress, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var loading by remember { mutableStateOf(progress().isLoading) }
    LaunchedEffect(ui.state.isPlaying, ui.state.currentTrack?.id) {
        while (true) {
            loading = progress().isLoading
            delay(250)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxl)) {
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() }, enabled = ui.state.currentTrack != null) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(36.dp))
        }
        // The label is on the button, so TalkBack still reads Pause while a load's ring stands
        // in for the icon, and the button stays where a finger expects it.
        val playLabel = stringResource(if (ui.state.isPlaying) SR.string.pause else SR.string.play)
        IconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
            modifier = Modifier.size(72.dp).semantics { contentDescription = playLabel },
        ) {
            if (loading) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Icon(
                    if (ui.state.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() }, enabled = ui.state.hasNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.next), tint = Color.White.copy(alpha = if (ui.state.hasNext) 1f else 0.35f), modifier = Modifier.size(36.dp))
        }
    }
}

/** Lyrics, Shuffle, Repeat, Crossfade, EQ, Sleep, Queue, Identify, Output; scrolls sideways when narrow. */
@Composable
private fun Tools(ui: PlayerUi, onShuffle: () -> Unit, onRepeat: () -> Unit, onSheet: (PlayerSheet) -> Unit, onIdentify: () -> Unit, onUndoIdentify: () -> Unit) {
    val context = LocalContext.current
    val hasTrack = ui.state.currentTrack != null
    val repeat = ui.state.repeatMode
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Tokens.Spacing.screenEdge),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md, Alignment.CenterHorizontally),
    ) {
        Tool(Icons.Filled.FormatQuote, stringResource(R.string.lyrics), enabled = hasTrack) { onSheet(PlayerSheet.LYRICS) }
        val on = stringResource(R.string.state_on)
        val off = stringResource(R.string.state_off)
        Tool(Icons.Filled.Shuffle, stringResource(R.string.shuffle), active = ui.state.isShuffled, state = if (ui.state.isShuffled) on else off, onClick = onShuffle)
        val repeatState = stringResource(
            when (repeat) {
                RepeatMode.OFF -> R.string.repeat_off
                RepeatMode.ALL -> R.string.repeat_all
                RepeatMode.ONE -> R.string.repeat_one
            },
        )
        Tool(if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, stringResource(R.string.repeat), active = repeat != RepeatMode.OFF, state = repeatState, onClick = onRepeat)
        Tool(Icons.Filled.Waves, stringResource(R.string.crossfade), active = ui.crossfadeOn, state = if (ui.crossfadeOn) on else off) { onSheet(PlayerSheet.CROSSFADE) }
        Tool(Icons.Filled.Equalizer, stringResource(R.string.eq), active = ui.equalizerOn, state = if (ui.equalizerOn) on else off) { onSheet(PlayerSheet.EQUALIZER) }
        Tool(Icons.Filled.Bedtime, stringResource(R.string.sleep), active = ui.sleep.isActive, state = if (ui.sleep.isActive) on else off) { onSheet(PlayerSheet.SLEEP) }
        Tool(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue), enabled = hasTrack) { onSheet(PlayerSheet.QUEUE) }
        if (ui.canIdentify) {
            // The same two actions as a song's menu, for the song playing.
            var menu by remember { mutableStateOf(false) }
            Box {
                Tool(Icons.Filled.ManageSearch, stringResource(R.string.identify)) { menu = true }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.identify_ellipsis)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        onClick = { menu = false; onIdentify() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.undo_identify_ellipsis)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
                        onClick = { menu = false; onUndoIdentify() },
                    )
                }
            }
        }
        Tool(Icons.Filled.SpeakerGroup, stringResource(R.string.output)) { showOutputSwitcher(context) }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, state: String? = null, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        active -> LocalPalette.current.accent
        else -> Color.White
    }
    TextButton(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        enabled = enabled,
        modifier = Modifier.heightIn(min = Tokens.minTarget).then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A tool that is on gets a plate behind its icon as well as the accent: not told by colour alone.
            Box(
                Modifier.background(if (active && enabled) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** The system's output switcher (speakers, Bluetooth, casting targets): the AirPlay button's place. */
fun showOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return
    }
    val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(panel) }
        .recoverCatching { context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
