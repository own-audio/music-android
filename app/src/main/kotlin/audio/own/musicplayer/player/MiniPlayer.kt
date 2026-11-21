@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.MiniPlayerBar
import audio.own.musicplayer.R
import audio.own.musicplayer.library.rememberCover
import audio.own.player.music.MusicPlayer
import kotlinx.coroutines.delay

/**
 * Above the navigation bar while something is loaded: cover, title, artist, play/pause and next,
 * the progress along the top with the buffered part lighter. After a relaunch it shows the last
 * queue's song paused, "play from where you left off".
 */
@Composable
fun MiniPlayer(
    state: MusicPlayer.State,
    progress: () -> Progress,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    tickMs: Long = 1_000,
) {
    val track = state.currentTrack ?: return
    var now by remember { mutableStateOf(progress()) }
    LaunchedEffect(track.id, state.isPlaying) {
        now = progress()
        while (true) {
            delay(tickMs)
            now = progress()
        }
    }
    val haptics = LocalHapticFeedback.current
    val duration = now.durationMs.toFloat().takeIf { it > 0 }
    val label = if (now.isRestored) stringResource(R.string.play_from_where_you_left_off, track.title) else null
    MiniPlayerBar(
        cover = rememberCover(track.artworkId, 40.dp),
        placeholder = Icons.Filled.MusicNote,
        title = track.title,
        subtitle = track.artist,
        isPlaying = state.isPlaying,
        isLoading = now.isLoading,
        progress = duration?.let { now.positionMs / it } ?: 0f,
        buffered = duration?.let { now.bufferedMs / it } ?: 0f,
        onToggle = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
        secondaryIcon = Icons.Filled.SkipNext,
        secondaryLabel = stringResource(R.string.next),
        onSecondary = onNext,
        secondaryEnabled = state.hasNext,
        onOpen = onOpen,
        modifier = if (label != null) modifier.semantics { contentDescription = label } else modifier,
    )
}
