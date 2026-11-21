@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import audio.own.music.library.model.Song
import audio.own.player.core.SleepTimer
import audio.own.player.music.MusicPlayer
import audio.own.player.music.RepeatMode
import audio.own.player.music.Track
import audio.own.player.music.TrackGain
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Where the song is, read on demand: the scrubber asks four times a second, the mini player once. */
data class Progress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** Waiting for audio: a spinner in place of Play/Pause. */
    val isLoading: Boolean = false,
    /** Put back after a relaunch and not opened yet: the mini player says "play from where you left off". */
    val isRestored: Boolean = false,
)

/**
 * What the screens need of the player. The real one drives the engine in the app's container
 * (queue edits carry songs with their gain and covers, which Media3 items can't), and the
 * transport through a `MediaController` on the service, so the session and its notification
 * follow every command.
 */
interface PlayerEngine {
    val state: StateFlow<MusicPlayer.State>
    val sleep: StateFlow<SleepTimer.State>
    val error: StateFlow<PlaybackException?>

    fun progress(): Progress
    fun setQueue(tracks: List<Track>, startTrackId: String?, shuffle: Boolean, containerId: String?)
    fun insertNext(tracks: List<Track>)
    fun append(tracks: List<Track>)
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun stop()
    fun setShuffled(on: Boolean)
    fun setRepeatMode(mode: RepeatMode)
    fun jumpTo(trackId: String)
    fun moveUpcoming(from: Int, to: Int)
    fun removeUpcoming(index: Int)
    fun startSleep(preset: SleepTimer.Preset)
    fun sleepAtEndOfTrack()
    fun extendSleep(bySecs: Double)
    fun cancelSleep()
    fun clearError()
}

/** The screens' side of playback: songs in, the engine's state out. Main thread only. */
class PlaybackModel(private val engine: PlayerEngine) {
    val state: StateFlow<MusicPlayer.State> get() = engine.state
    val sleep: StateFlow<SleepTimer.State> get() = engine.sleep
    val error: StateFlow<PlaybackException?> get() = engine.error

    private val _firstPlay = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Every play the listener starts: the activity asks for the notification permission on the first. */
    val plays: SharedFlow<Unit> = _firstPlay.asSharedFlow()

    fun progress(): Progress = engine.progress()

    /** Plays [songs] from [startId] (else the top); a list that is to be shuffled starts anywhere. */
    fun play(songs: List<Song>, startId: String? = null, shuffle: Boolean = false, containerId: String? = null) {
        if (songs.isEmpty()) return
        engine.clearError()
        engine.setQueue(songs.map(::track), startId, shuffle, containerId)
        _firstPlay.tryEmit(Unit)
    }

    /** Play Next ([next]) or Add to Queue. With nothing loaded, it plays. */
    fun enqueue(songs: List<Song>, next: Boolean) {
        if (songs.isEmpty()) return
        if (engine.state.value.currentTrack == null) return play(songs)
        val tracks = songs.map(::track)
        if (next) engine.insertNext(tracks) else engine.append(tracks)
    }

    fun togglePlayPause() {
        if (engine.state.value.isPlaying) {
            engine.pause()
        } else {
            engine.clearError()
            engine.play()
            _firstPlay.tryEmit(Unit)
        }
    }

    fun next() = engine.next()
    fun previous() = engine.previous()
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun stop() = engine.stop()
    fun toggleShuffle() = engine.setShuffled(!engine.state.value.isShuffled)
    fun cycleRepeat() = engine.setRepeatMode(engine.state.value.repeatMode.next())
    fun jumpTo(trackId: String) = engine.jumpTo(trackId)
    fun moveUpcoming(from: Int, to: Int) = engine.moveUpcoming(from, to)
    fun removeUpcoming(index: Int) = engine.removeUpcoming(index)
    fun startSleep(preset: SleepTimer.Preset) = engine.startSleep(preset)
    fun sleepAtEndOfTrack() = engine.sleepAtEndOfTrack()
    fun extendSleep() = engine.extendSleep(600.0)
    fun cancelSleep() = engine.cancelSleep()

    companion object {
        fun track(song: Song): Track = Track(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSecs = song.durationMs?.let { (it / 1000).toInt() },
            artworkId = song.coverArt,
            replayGain = song.gain?.takeIf { !it.isEmpty }?.let { TrackGain(it.track, it.album, it.trackPeak, it.albumPeak) },
        )
    }
}

/** [PlayerEngine] over the app's [PlaybackStack] and a controller connected to the service. */
@UnstableApi
class StackEngine(private val stack: PlaybackStack) : PlayerEngine {
    private val music get() = stack.music
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override val state: StateFlow<MusicPlayer.State> get() = music.state
    override val sleep: StateFlow<SleepTimer.State> get() = music.sleepTimer.state
    override val error: StateFlow<PlaybackException?> get() = stack.error

    /** Binds the service while a screen shows, so the session is there before the first Play. */
    fun connect(context: Context) {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
    }

    /** The controller once connected; the engine's own player until then (same process, same thread). */
    private val player: Player get() = controller ?: music.player

    override fun progress(): Progress {
        val p = music.player
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((music.state.value.currentTrack?.durationSecs ?: 0) * 1000L)
        return Progress(
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            bufferedMs = p.bufferedPosition.coerceAtLeast(0),
            isLoading = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING,
            isRestored = p.playbackState == Player.STATE_IDLE && !p.playWhenReady && p.mediaItemCount > 0,
        )
    }

    override fun setQueue(tracks: List<Track>, startTrackId: String?, shuffle: Boolean, containerId: String?) =
        music.setQueue(tracks, startTrackId, shuffle = shuffle, containerId = containerId)

    override fun insertNext(tracks: List<Track>) = music.insertNext(tracks)
    override fun append(tracks: List<Track>) = music.append(tracks)
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun next() = player.seekToNext()
    override fun previous() = player.seekToPrevious()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun stop() = music.stop()
    override fun setShuffled(on: Boolean) = music.setShuffled(on)
    override fun setRepeatMode(mode: RepeatMode) = music.setRepeatMode(mode)
    override fun jumpTo(trackId: String) = music.jumpTo(trackId)
    override fun moveUpcoming(from: Int, to: Int) = music.moveUpcoming(listOf(from), if (to > from) to + 1 else to)
    override fun removeUpcoming(index: Int) = music.removeUpcoming(listOf(index))
    override fun startSleep(preset: SleepTimer.Preset) = music.sleepTimer.start(preset)
    override fun sleepAtEndOfTrack() = music.sleepTimer.startUntilEndOfItem()
    override fun extendSleep(bySecs: Double) = music.sleepTimer.extend(bySecs)
    override fun cancelSleep() = music.sleepTimer.cancel()
    override fun clearError() = stack.clearError()
}
