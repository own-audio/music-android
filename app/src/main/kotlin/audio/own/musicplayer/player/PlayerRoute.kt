package audio.own.musicplayer.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.music.library.FavoriteKind
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Lyrics
import audio.own.music.library.model.Song
import audio.own.musicplayer.AppContainer
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.app.userMessage
import audio.own.player.music.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The lyrics of the song playing, every version the server has. */
class LyricsViewModel(private val sourceOf: (String) -> MusicSource?) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Lyrics>>>(Load.Loading)
    val state: StateFlow<Load<List<Lyrics>>> = _state.asStateFlow()
    private var trackId: String? = null

    fun load(track: Track, force: Boolean = false) {
        if (!force && track.id == trackId) return
        trackId = track.id
        _state.value = Load.Loading
        viewModelScope.launch {
            _state.value = try {
                val source = sourceOf(track.id) ?: throw audio.own.music.library.SourceError.NotFound()
                Load.Loaded(source.lyrics(Song(id = track.id, title = track.title, artist = track.artist, album = track.album, durationMs = track.durationSecs?.let { it * 1000L })))
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: Exception) {
                Load.Failed(error.userMessage())
            }
        }
    }
}

/** The player destination: always dark, with its sheets. Closes itself once nothing is loaded. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerRoute(container: AppContainer, onClose: () -> Unit, onError: (Throwable) -> Unit) {
    val model = container.playbackModel
    val state by model.state.collectAsStateWithLifecycle()
    val sleep by model.sleep.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val settings by container.playbackSettings.settings.collectAsStateWithLifecycle()
    val eq by container.playbackSettings.equalizer.settings.collectAsStateWithLifecycle()
    val favorites by container.library.favorites.collectAsStateWithLifecycle()
    val accent by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    val scope = rememberCoroutineScope()
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    val track = state.currentTrack

    LaunchedEffect(track == null) { if (track == null) onClose() }
    if (track == null) return

    val starred = remember(track.id, favorites) { container.library.isFavorite(track.id, false) }
    val errorText = error?.let { stringResource(R.string.could_not_play) }
    OwnAudioTheme(accent = accent, darkTheme = true) {
        PlayerContent(
            ui = PlayerUi(state, sleep, errorText, starred, settings.crossfadeEnabled, eq.enabled),
            progress = model::progress,
            onClose = onClose,
            onStop = model::stop,
            onToggle = model::togglePlayPause,
            onNext = model::next,
            onPrevious = model::previous,
            onSeek = model::seekTo,
            onFavorite = {
                scope.launch { runCatching { container.library.setFavorite(FavoriteKind.SONG, track.id, !starred) }.onFailure(onError) }
            },
            onShuffle = model::toggleShuffle,
            onRepeat = model::cycleRepeat,
            onSheet = { sheet = it },
        )
        when (sheet) {
            PlayerSheet.QUEUE -> PlayerSheetFrame(stringResource(R.string.queue), onDismiss = { sheet = null }, fullHeight = true) {
                QueueContent(state, onJump = { model.jumpTo(it); sheet = null }, onMove = model::moveUpcoming, onRemove = model::removeUpcoming)
            }
            PlayerSheet.SLEEP -> PlayerSheetFrame(stringResource(R.string.sleep_timer), onDismiss = { sheet = null }) {
                SleepContent(
                    sleep,
                    onPreset = { model.startSleep(it); sheet = null },
                    onEndOfTrack = { model.sleepAtEndOfTrack(); sheet = null },
                    onExtend = model::extendSleep,
                    onCancel = { model.cancelSleep(); sheet = null },
                )
            }
            PlayerSheet.CROSSFADE -> PlayerSheetFrame(stringResource(R.string.crossfade), onDismiss = { sheet = null }) {
                CrossfadeContent(
                    settings,
                    onCrossfade = { on -> container.playbackSettings.update { it.copy(crossfadeEnabled = on) } },
                    onFade = { secs -> container.playbackSettings.update { it.copy(fadeSecs = secs) } },
                    onLeveling = { mode -> container.playbackSettings.update { it.copy(replayGain = mode) } },
                )
            }
            PlayerSheet.LYRICS -> {
                val vm = viewModel { LyricsViewModel(container.sources::sourceOf) }
                val lyrics by vm.state.collectAsStateWithLifecycle()
                var choice by remember(track.id) { mutableIntStateOf(0) }
                LaunchedEffect(track.id) { vm.load(track) }
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { sheet = null },
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = androidx.compose.ui.graphics.Color.Black,
                ) {
                    SheetBody(track.title, onDone = { sheet = null }, leading = {
                        val all = (lyrics as? Load.Loaded)?.value.orEmpty()
                        if (all.size > 1) LyricsVersionPicker(all, choice) { choice = it }
                    }) {
                        LyricsContent(lyrics, model::progress, model::seekTo, onRetry = { vm.load(track, force = true) }, choice = choice, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            PlayerSheet.EQUALIZER -> androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                val store = container.playbackSettings.equalizer
                SheetBody(stringResource(R.string.equalizer), onDone = { sheet = null }, leading = {
                    androidx.compose.material3.TextButton(onClick = store::reset, enabled = eq.enabled) { androidx.compose.material3.Text(stringResource(R.string.reset)) }
                }) {
                    EqualizerBody(container)
                }
            }
            null -> Unit
        }
    }
}

/** The equalizer's controls on the store, for the player's sheet and the Settings page. */
@UnstableApi
@Composable
fun EqualizerBody(container: AppContainer, modifier: Modifier = Modifier) {
    val store = container.playbackSettings.equalizer
    val eq by store.settings.collectAsStateWithLifecycle()
    EqualizerContent(
        eq,
        onEnabled = store::setEnabled,
        onPreamp = store::setPreampDb,
        onBand = store::setBandGainDb,
        onRelease = store::save,
        onPreset = store::applyPreset,
        modifier = modifier,
    )
}
