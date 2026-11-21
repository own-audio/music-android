package audio.own.player.music

import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import audio.own.player.core.AudioFocusOnFirstPlay
import audio.own.player.core.ContentKind
import audio.own.player.core.IdleDecoderRelease
import audio.own.player.core.JsonFile
import audio.own.player.core.PlaybackLinkRequest
import audio.own.player.core.PlaybackUris
import audio.own.player.core.PlayerSetup
import audio.own.player.core.ResumeStore
import audio.own.player.core.SleepTimer
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The music engine: ExoPlayer with the iOS app's queue semantics, gapless playback, crossfade,
 * ReplayGain, the equalizer, plays, concerts, the sleep timer and the last queue.
 *
 * **ExoPlayer owns the playlist**, so gapless playback, the notification and Android Auto come
 * with it. It holds the songs in *play order*; [PlayQueue] keeps the list order beside it, so
 * shuffle can keep what was heard and turning it off restores the album's order. Every edit
 * changes ExoPlayer's items before and after the current one and never the current item itself:
 * replacing it would cut the song and report a seek (fixed in iOS 1.0.1 for the same reason).
 *
 * **Two decks** (two ExoPlayers) exist for crossfade only: the next song starts on the idle
 * deck while the active one fades out, the way music-ios `PlaybackEngine` uses two player
 * nodes. The session's player is the active deck's [Player]; when a fade starts the active deck
 * changes and [Listener.onActivePlayerChanged] tells the service to call
 * `MediaSession.setPlayer`.
 *
 * Use it on the main thread (the decks' application looper).
 */
@UnstableApi
class MusicPlayer(private val config: Config) {

    class Config(
        /** Makes one deck; called twice. `PlayerSetup.buildPlayer` in the app. */
        val newDeck: () -> ExoPlayer,
        val items: MusicMediaItems = MusicMediaItems(),
        /** Whether every byte of a stream is in the listening cache (`PlaybackDataSources.isFullyCached`). */
        val isFullyCached: (PlaybackLinkRequest) -> Boolean = { false },
        /** The listening cache's data source, for prefetching the next song; null: no prefetch. */
        val prefetchSource: CacheDataSource.Factory? = null,
        val prefetchExecutor: Executor = Executors.newSingleThreadExecutor(),
        /** The mobile-data cap for new queue entries; null: the original. */
        val maxBitrateKbps: () -> Int? = { null },
        /** Offline mode (or metered + Downloaded Only): only songs on the phone play. */
        val isOffline: () -> Boolean = { false },
        /** Whether caching ahead is allowed now (on metered networks the app may say no). */
        val canPrefetch: () -> Boolean = { true },
        val lastQueue: JsonFile<LastQueue>? = null,
        val resumeStore: ResumeStore? = null,
        val effects: DeckEffectsFactory = DynamicsProcessingEffects.FACTORY,
        val idleReleaseMs: Long = IdleDecoderRelease.DEFAULT_DELAY_MS,
    )

    interface Listener {
        /** The session's player changed (a crossfade began): `session.player = player`. */
        fun onActivePlayerChanged(player: Player) {}

        /** Now playing and plays, for the source's scrobble or `POST /me/plays`. */
        fun onScrobble(event: ScrobbleTracker.Event) {}

        /** Every 15 s while playing, and on pause: Jellyfin progress, concert resume points. */
        fun onProgress(trackId: String, positionMs: Long, isPlaying: Boolean) {}

        /** A song failed to load; [stopped] after the third failure in a row. */
        fun onPlaybackError(error: PlaybackException, stopped: Boolean) {}
    }

    data class State(
        val currentTrack: Track? = null,
        val isPlaying: Boolean = false,
        /** "Track 1 of 5" in play order. */
        val position: PlayQueue.Position? = null,
        /** The same in the list as a screen shows it. */
        val listPosition: PlayQueue.Position? = null,
        val played: List<Track> = emptyList(),
        val upcoming: List<Track> = emptyList(),
        val isShuffled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
        val format: FormatBadge? = null,
        /** The ReplayGain adjustment applied to the current song, for the badge. */
        val replayGainDb: Double = 0.0,
        val isCrossfading: Boolean = false,
        val chapters: List<TrackChapter> = emptyList(),
        val chapterIndex: Int? = null,
    )

    var listener: Listener = object : Listener {}

    private val attributes = PlayerSetup.audioAttributes(ContentKind.MUSIC)
    private val focus = AudioFocusOnFirstPlay(attributes)

    private val deckA = Deck(config.newDeck())
    private val deckB = Deck(config.newDeck())
    private var active = deckA

    @VisibleForTesting internal val decks: List<ExoPlayer> get() = listOf(deckA.exo, deckB.exo)
    @VisibleForTesting internal val activeExo: ExoPlayer get() = active.exo

    /** What the media session plays through. Changes when a crossfade begins. */
    val player: Player get() = active.player

    private val handler = Handler(deckA.exo.applicationLooper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())

    val sleepTimer = SleepTimer(scope)

    private var queue = PlayQueue(emptyList())
    private val trackById = HashMap<String, Track>()
    private var containerId: String? = null
    private var repeatMode = RepeatMode.OFF
    private var replayGainMode = ReplayGainMode.DEFAULT
    private var equalizer = EqualizerSettings()
    private var crossfadeSecs: Double? = null

    private val tagGains = HashMap<String, TrackGain>()
    private val formats = HashMap<String, FormatBadge>()
    private val chapters = HashMap<String, List<TrackChapter>>()
    private var shownChapter: Pair<String, Int?>? = null

    private var failures = 0
    private var lastTrackId: String? = null
    private var lastProgressAt = 0L

    private val scrobbles = ScrobbleTracker(send = { listener.onScrobble(it) })

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        sleepTimer.onExpire = { active.exo.pause() }
        scope.launch {
            sleepTimer.state.collect { timer ->
                // End of Track: ExoPlayer pauses at the song's end instead of moving on, and the
                // pause is where the timer is consumed, before anything else starts.
                for (deck in listOf(deckA, deckB)) deck.exo.pauseAtEndOfMediaItems = timer.stopsAtEndOfItem
                if (timer.stopsAtEndOfItem) cancelScheduledFade()
            }
        }
    }

    // Queue

    /**
     * Plays [tracks] from [startTrackId] at [positionMs]. The position is given to
     * `setMediaItems` with the item, never by a seek after `prepare()`, which ExoPlayer drops.
     * With [shuffle] the order is shuffled from its top and the start song is whatever comes first.
     */
    fun setQueue(
        tracks: List<Track>,
        startTrackId: String? = null,
        positionMs: Long = 0,
        shuffle: Boolean = false,
        containerId: String? = null,
        play: Boolean = true,
    ) {
        load(tracks, startTrackId, positionMs, shuffle, containerId)
        val exo = active.exo
        if (exo.mediaItemCount == 0) return
        exo.prepare()
        skipUnplayableIfOffline()
        if (play) {
            beforePlay(active)
            exo.play()
        }
    }

    private fun load(tracks: List<Track>, startTrackId: String?, positionMs: Long, shuffle: Boolean, containerId: String?) {
        cancelFade()
        cancelPrefetch()
        for (track in tracks) trackById[track.id] = track
        queue = PlayQueue(tracks, startTrackId)
        if (shuffle) queue.shuffleFromStart()
        this.containerId = containerId
        failures = 0
        val exo = active.exo
        val order = playOrder()
        if (order.isEmpty()) {
            exo.clearMediaItems()
        } else {
            exo.setMediaItems(order.map(::mediaItem), queue.played.size, if (shuffle) 0 else positionMs.coerceAtLeast(0))
            exo.shuffleModeEnabled = queue.isShuffled
        }
        updateState()
        saveLastQueue()
    }

    /** Play Next: right after the current song; a song already queued moves there. */
    fun insertNext(tracks: List<Track>) = edit(tracks) { queue.insertNext(tracks) }

    /** Add to Queue: at the end; a song already waiting moves there. */
    fun append(tracks: List<Track>) = edit(tracks) { queue.append(tracks) }

    /** Rows of [State.upcoming], as a drag moves them (see [PlayQueue.moveUpcoming]). */
    fun moveUpcoming(offsets: Collection<Int>, destination: Int) = edit(emptyList()) { queue.moveUpcoming(offsets, destination) }

    fun removeUpcoming(offsets: Collection<Int>) = edit(emptyList()) { queue.removeUpcoming(offsets) }

    /** Plays the queued song [trackId] from its start. */
    fun jumpTo(trackId: String) {
        if (queue.jumpTo(trackId) == null) return
        cancelFade()
        active.exo.seekTo(queue.played.size, 0)
        beforePlay(active)
        active.exo.play()
    }

    /** Shuffle keeps the current song and what was heard; only what is still to come moves. */
    fun setShuffled(shuffled: Boolean) {
        if (queue.currentTrack == null) return
        queue.setShuffled(shuffled)
        syncPlaylist()
        active.exo.shuffleModeEnabled = shuffled
        updateState()
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        active.exo.repeatMode = mode.toPlayer()
        updateState()
    }

    /**
     * Rebuilds the upcoming entries after the mobile-data setting or the network changed (and
     * after a download finished, so the song plays from its file). The current song keeps
     * playing as it was opened.
     */
    fun refreshUpcoming() {
        syncPlaylist()
        updateState()
    }

    private fun edit(added: List<Track>, change: () -> Unit) {
        if (queue.currentTrack == null) {
            if (added.isNotEmpty()) setQueue(added, play = false)
            return
        }
        for (track in added) trackById[track.id] = track
        change()
        syncPlaylist()
        updateState()
        saveLastQueue()
    }

    private fun playOrder(): List<Track> = queue.played + listOfNotNull(queue.currentTrack) + queue.upcoming

    /**
     * Makes ExoPlayer's items match the queue's play order around the current item, changing
     * only the stretch that differs on each side, so the next song (already buffered for
     * gapless) stays when it didn't change.
     */
    private fun syncPlaylist() {
        val exo = active.exo
        val current = queue.currentTrack ?: return
        val desired = playOrder()
        if (exo.currentMediaItem?.mediaId != current.id) {
            exo.setMediaItems(desired.map(::mediaItem), queue.played.size, 0)
            return
        }
        val cur = queue.played.size
        replaceRange(exo, 0, exo.currentMediaItemIndex, desired.subList(0, cur))
        replaceRange(exo, exo.currentMediaItemIndex + 1, exo.mediaItemCount, desired.subList(cur + 1, desired.size))
        prefetching?.let { if (nextRequest() != it.request) cancelPrefetch() }
    }

    private fun replaceRange(exo: ExoPlayer, from: Int, to: Int, tracks: List<Track>) {
        val wanted = tracks.map(::mediaItem)
        val have = (from until to).map(exo::getMediaItemAt)
        var prefix = 0
        while (prefix < have.size && prefix < wanted.size && same(have[prefix], wanted[prefix])) prefix++
        var suffix = 0
        while (suffix < have.size - prefix && suffix < wanted.size - prefix &&
            same(have[have.size - 1 - suffix], wanted[wanted.size - 1 - suffix])
        ) suffix++
        val start = from + prefix
        val end = to - suffix
        val middle = wanted.subList(prefix, wanted.size - suffix)
        when {
            start == end && middle.isEmpty() -> Unit
            start == end -> exo.addMediaItems(start, middle)
            middle.isEmpty() -> exo.removeMediaItems(start, end)
            else -> exo.replaceMediaItems(start, end, middle)
        }
    }

    private fun same(a: MediaItem, b: MediaItem) = a.mediaId == b.mediaId && a.localConfiguration?.uri == b.localConfiguration?.uri

    private fun mediaItem(track: Track): MediaItem = config.items.mediaItem(track, config.maxBitrateKbps())

    // Transport

    /** Next: the next concert chapter inside the track, else the next song (whatever Repeat says). */
    fun skipToNext() {
        val exo = active.exo
        currentChapters()?.let { list ->
            ChapterNavigation.next(list, exo.currentPosition / 1000.0)?.let {
                cancelFade()
                exo.seekTo((it * 1000).toLong())
                return
            }
        }
        cancelFade()
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
    }

    /** Previous: more than 3 s into a song (or chapter) restarts it; earlier goes back one. */
    fun skipToPrevious() {
        val exo = active.exo
        cancelFade()
        val positionSecs = exo.currentPosition / 1000.0
        currentChapters()?.let { list ->
            ChapterNavigation.previous(list, positionSecs)?.let {
                exo.seekTo((it * 1000).toLong())
                return
            }
        }
        if (positionSecs > RESTART_THRESHOLD_SECS || !exo.hasPreviousMediaItem()) exo.seekTo(0) else exo.seekToPreviousMediaItem()
    }

    private fun beforePlay(deck: Deck) {
        focus.beforePlay(deck.exo)
        IdleDecoderRelease.prepareIfIdle(deck.exo)
    }

    // Settings

    fun setReplayGainMode(mode: ReplayGainMode) {
        replayGainMode = mode
        applyVolume(deckA)
        applyVolume(deckB)
        updateState()
    }

    fun setEqualizer(settings: EqualizerSettings) {
        equalizer = settings
        applyVolume(deckA)
        applyVolume(deckB)
    }

    /** Crossfade length in seconds (1–12), or null for gapless playback (the default). */
    fun setCrossfade(secs: Double?) {
        crossfadeSecs = secs?.coerceIn(CrossfadeRules.MIN_SECS, CrossfadeRules.MAX_SECS)
        if (secs == null) cancelScheduledFade()
    }

    /** A concert recorded as one file: next and previous move between its chapters. */
    fun setChapters(trackId: String, list: List<TrackChapter>) {
        if (list.isEmpty()) chapters.remove(trackId) else chapters[trackId] = list.sortedBy { it.startSecs }
        if (shownChapter?.first == trackId) shownChapter = null
        updateChapterMetadata()
        updateState()
    }

    private fun currentChapters(): List<TrackChapter>? = active.exo.currentMediaItem?.mediaId?.let { chapters[it] }

    // Last queue and resumption

    /** The app went to the background: remember where it was. */
    fun onBackground() {
        saveLastQueue()
        saveResume()
    }

    /**
     * Puts the last queue back, paused and not prepared: the mini player shows it, and nothing
     * opens on the network (or takes audio focus) until Play.
     */
    fun restoreLastQueue(): Boolean {
        val last = config.lastQueue?.read() ?: return false
        if (last.tracks.isEmpty()) return false
        load(last.tracks, last.currentTrackId, last.positionMs, false, last.containerId)
        setRepeatMode(last.repeatMode)
        return true
    }

    /** For `onPlaybackResumption`: the last queue as Media3 items. */
    fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? {
        val last = config.lastQueue?.read() ?: return null
        if (last.tracks.isEmpty()) return null
        for (track in last.tracks) trackById[track.id] = track
        val index = last.tracks.indexOfFirst { it.id == last.currentTrackId }.coerceAtLeast(0)
        return MediaSession.MediaItemsWithStartPosition(last.tracks.map(::mediaItem), index, last.positionMs)
    }

    private fun saveLastQueue() {
        val store = config.lastQueue ?: return
        val current = queue.currentTrack ?: return
        store.write(LastQueue(playOrder(), current.id, active.exo.currentPosition.coerceAtLeast(0), containerId, repeatMode))
    }

    private fun clearLastQueue() {
        config.lastQueue?.delete()
    }

    private fun saveResume() {
        val container = containerId ?: return
        val current = queue.currentTrack ?: return
        config.resumeStore?.saveResume(container, current.id, active.exo.currentPosition.coerceAtLeast(0))
    }

    /** Stop: the queue is gone, and so is the last queue. */
    fun stop() {
        cancelFade()
        cancelPrefetch()
        active.exo.stop()
        active.exo.clearMediaItems()
        queue = PlayQueue(emptyList())
        clearLastQueue()
        updateState()
    }

    fun release() {
        cancelFade()
        cancelPrefetch()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        for (deck in listOf(deckA, deckB)) {
            deck.effects?.release()
            deck.idle.dispose()
            deck.exo.release()
        }
    }

    // Events of the active deck

    private fun onTransition(deck: Deck, item: MediaItem?, reason: Int) {
        val id = item?.mediaId
        val previous = lastTrackId
        if (previous != null && (previous != id || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            scrobbles.stopped(previous, isEnd = true)
        }
        if (previous != id) shownChapter = null
        lastTrackId = id
        if (id != null) {
            queue.jumpTo(id)
            if (deck.exo.isPlaying && (previous != id || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
                scrobbles.started(id, trackById[id]?.durationSecs)
            }
            if (prefetching?.request?.mediaId == id) prefetching = null
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) skipUnplayableIfOffline()
            saveLastQueue()
            saveResume()
        }
        applyVolume(deck)
        updateState()
    }

    private fun onPlayingChanged(deck: Deck, isPlaying: Boolean) {
        val id = deck.exo.currentMediaItem?.mediaId
        if (id != null) {
            if (isPlaying) {
                lastTrackId = id
                scrobbles.started(id, trackById[id]?.durationSecs)
            } else {
                scrobbles.stopped(id, isEnd = false)
                if (!deck.exo.playWhenReady) {
                    listener.onProgress(id, deck.exo.currentPosition, false)
                    saveLastQueue()
                    saveResume()
                }
            }
        }
        handler.removeCallbacks(tick)
        if (isPlaying) handler.post(tick)
        updateState()
    }

    private fun onPlaybackState(deck: Deck, state: Int) {
        when (state) {
            Player.STATE_READY -> failures = 0
            Player.STATE_ENDED -> {
                deck.exo.currentMediaItem?.mediaId?.let { scrobbles.stopped(it, isEnd = true) }
                lastTrackId = null
                clearLastQueue()
                containerId?.let { config.resumeStore?.clearResume(it) }
            }
        }
        updateState()
    }

    /** The pause End of Track asked for (pauseAtEndOfMediaItems): consumed before any advance. */
    private fun onPausedAtEndOfItem(deck: Deck) {
        val exo = deck.exo
        if (sleepTimer.consumeEndOfItem()) {
            // Stays paused, but on the next song's start, so Play goes on with the queue.
            val next = nextIndex(exo)
            if (next != null && next != exo.currentMediaItemIndex) exo.seekTo(next, 0)
        } else {
            exo.play()
        }
    }

    private fun onError(deck: Deck, error: PlaybackException) {
        failures += 1
        val exo = deck.exo
        val next = if (failures < MAX_FAILURES) nextPlayableIndex(exo) else null
        if (next == null) {
            failures = 0
            listener.onPlaybackError(error, stopped = true)
            return
        }
        listener.onPlaybackError(error, stopped = false)
        exo.seekTo(next, 0)
        exo.prepare()
    }

    // Offline

    private fun isPlayable(item: MediaItem): Boolean {
        if (!config.isOffline()) return true
        return isFullyAvailable(item)
    }

    private fun isFullyAvailable(item: MediaItem): Boolean {
        val uri: Uri = item.localConfiguration?.uri ?: return false
        val request = PlaybackUris.parse(uri) ?: return true
        return config.isFullyCached(request) || config.isFullyCached(request.copy(maxBitrateKbps = null))
    }

    private fun skipUnplayableIfOffline() {
        val exo = active.exo
        val item = exo.currentMediaItem ?: return
        if (isPlayable(item)) return
        val next = nextPlayableIndex(exo)
        if (next != null) exo.seekTo(next, 0) else exo.pause()
    }

    private fun nextPlayableIndex(exo: ExoPlayer): Int? {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return null
        val navigationRepeat = if (repeatMode == RepeatMode.ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        var index = exo.currentMediaItemIndex
        repeat(timeline.windowCount) {
            index = timeline.getNextWindowIndex(index, navigationRepeat, false)
            if (index == C.INDEX_UNSET || index == exo.currentMediaItemIndex) return null
            if (isPlayable(exo.getMediaItemAt(index))) return index
        }
        return null
    }

    /** What plays after the current song when it ends on its own (Repeat One: itself). */
    private fun nextIndex(exo: ExoPlayer): Int? {
        if (exo.mediaItemCount == 0) return null
        if (repeatMode == RepeatMode.ONE) return exo.currentMediaItemIndex
        val index = exo.currentTimeline.getNextWindowIndex(exo.currentMediaItemIndex, repeatMode.toPlayer(), false)
        return index.takeIf { it != C.INDEX_UNSET }
    }

    private fun nextRequest(): PlaybackLinkRequest? {
        val exo = active.exo
        val index = nextIndex(exo)?.takeIf { it != exo.currentMediaItemIndex } ?: return null
        return PlaybackUris.parse(exo.getMediaItemAt(index).localConfiguration?.uri)
    }

    // The tick: prefetch, crossfade, chapters, plays, progress

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val exo = active.exo
            if (!exo.isPlaying) return
            val duration = exo.duration.takeIf { it != C.TIME_UNSET }
            val position = exo.currentPosition
            if (duration != null && (crossfadeSecs != null || duration - position <= PREFETCH_LEAD_MS)) prefetchNext()
            scheduleFadeIfDue(duration, position)
            updateChapterMetadata()
            scrobbles.tick()
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                exo.currentMediaItem?.mediaId?.let { listener.onProgress(it, position, true) }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private class Prefetch(val request: PlaybackLinkRequest, val writer: CacheWriter)

    private var prefetching: Prefetch? = null

    /**
     * Caches the next song while this one plays, so the gapless join (or a crossfade, which
     * needs the whole file) holds on a slow network.
     */
    private fun prefetchNext() {
        val source = config.prefetchSource ?: return
        if (!config.canPrefetch()) return
        val request = nextRequest() ?: return
        if (prefetching?.request == request) return
        cancelPrefetch()
        if (config.isFullyCached(request)) return
        val writer = CacheWriter(source.createDataSourceForDownloading(), DataSpec(PlaybackUris.forRequest(request)), null, null)
        prefetching = Prefetch(request, writer)
        config.prefetchExecutor.execute {
            try {
                writer.cache()
            } catch (_: IOException) {
                // Cancelled, or the network failed: the song streams when it plays.
            }
        }
    }

    private fun cancelPrefetch() {
        prefetching?.writer?.cancel()
        prefetching = null
    }

    // Crossfade

    private class Fade(val outgoing: Deck, val incoming: Deck, val stepMs: Long) {
        var step = 0
    }

    private var fade: Fade? = null
    private var fadeStart: Runnable? = null

    private fun scheduleFadeIfDue(durationMs: Long?, positionMs: Long) {
        if (fadeStart != null) return
        val exo = active.exo
        val next = nextIndex(exo)
        val situation = CrossfadeRules.Situation(
            fadeSecs = crossfadeSecs,
            isPlaying = exo.isPlaying,
            isFading = fade != null,
            sleepsAtEndOfTrack = sleepTimer.stopsAtEndOfItem,
            hasNext = next != null,
            nextFullyAvailable = next != null && isFullyAvailable(exo.getMediaItemAt(next)),
            durationMs = durationMs,
            positionMs = positionMs,
        )
        when (val decision = CrossfadeRules.decide(situation, TICK_MS)) {
            CrossfadeRules.Decision.No -> Unit
            is CrossfadeRules.Decision.StartIn -> if (decision.delayMs == 0L) {
                beginCrossfade()
            } else {
                // On the player's looper, so a fade due while the app is in the background
                // starts on time, not with the next tick.
                val start = Runnable {
                    fadeStart = null
                    beginCrossfade()
                }
                fadeStart = start
                handler.postDelayed(start, decision.delayMs)
            }
        }
    }

    /**
     * Starts the next song on the idle deck at volume 0 and ramps the two over the fade. The
     * incoming song is what plays from here: title, cover and position switch now, as on iOS.
     */
    @VisibleForTesting
    internal fun beginCrossfade(): Boolean {
        val secs = crossfadeSecs ?: return false
        if (fade != null || sleepTimer.stopsAtEndOfItem) return false
        val outgoing = active
        val exo = outgoing.exo
        val next = nextIndex(exo) ?: return false
        if (!isFullyAvailable(exo.getMediaItemAt(next))) return false
        val items = (0 until exo.mediaItemCount).map(exo::getMediaItemAt)
        val incoming = if (outgoing === deckA) deckB else deckA

        // One deck asks for audio focus at a time: two players of one app would take it from
        // each other, and the outgoing one would pause mid-fade.
        focus.release(exo)
        active = incoming
        incoming.fade = 0f
        incoming.exo.repeatMode = repeatMode.toPlayer()
        incoming.exo.setMediaItems(items, next, 0)
        incoming.exo.shuffleModeEnabled = queue.isShuffled
        incoming.exo.prepare()
        beforePlay(incoming)
        incoming.exo.play()

        // The outgoing deck plays out its own song only.
        exo.repeatMode = Player.REPEAT_MODE_OFF
        val current = exo.currentMediaItemIndex
        if (current + 1 < exo.mediaItemCount) exo.removeMediaItems(current + 1, exo.mediaItemCount)
        if (current > 0) exo.removeMediaItems(0, current)

        val started = Fade(outgoing, incoming, (secs * 1000 / CrossfadeRules.STEPS).toLong())
        fade = started
        applyVolume(incoming)
        listener.onActivePlayerChanged(incoming.player)
        handler.postDelayed(fadeStep, started.stepMs)
        updateState()
        return true
    }

    private val fadeStep: Runnable = object : Runnable {
        override fun run() {
            val current = fade ?: return
            current.step += 1
            val (out, `in`) = CrossfadeRules.ramp(current.step)
            current.outgoing.fade = out
            current.incoming.fade = `in`
            applyVolume(current.outgoing)
            applyVolume(current.incoming)
            if (current.step >= CrossfadeRules.STEPS) finishFade() else handler.postDelayed(this, current.stepMs)
        }
    }

    /** Ends a fade, finished or cut short by a skip or seek: the incoming song at full volume. */
    private fun finishFade() {
        val done = fade ?: return
        fade = null
        handler.removeCallbacks(fadeStep)
        done.outgoing.exo.stop()
        done.outgoing.exo.clearMediaItems()
        done.outgoing.fade = 1f
        done.outgoing.effects?.release()
        done.outgoing.effects = null
        done.incoming.fade = 1f
        applyVolume(done.incoming)
        updateState()
    }

    private fun cancelScheduledFade() {
        fadeStart?.let(handler::removeCallbacks)
        fadeStart = null
    }

    private fun cancelFade() {
        cancelScheduledFade()
        finishFade()
    }

    // Gain

    private fun gainDb(mediaId: String?): Double {
        if (mediaId == null) return 0.0
        return ReplayGain.gainDb(trackById[mediaId]?.replayGain ?: tagGains[mediaId], replayGainMode)
    }

    /**
     * ReplayGain as the deck's volume (times the fade); a positive gain, which volume can't
     * give, goes to the effects' input gain together with the equalizer.
     */
    private fun applyVolume(deck: Deck) {
        val (volume, boostDb) = GainMath.split(gainDb(deck.exo.currentMediaItem?.mediaId))
        deck.exo.volume = volume * deck.fade
        val effects = deck.effects ?: config.effects.create(deck.exo.audioSessionId)?.also { deck.effects = it }
        effects?.apply(equalizer, boostDb)
    }

    private fun onFormat(deck: Deck, tracks: Tracks) {
        val id = deck.exo.currentMediaItem?.mediaId ?: return
        val format = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map(group::getTrackFormat) }
            .firstOrNull() ?: return
        FormatBadge.from(format)?.let { formats[id] = it }
        format.metadata?.let { onTags(deck, id, it) }
        updateState()
    }

    private fun onTags(deck: Deck, mediaId: String, metadata: Metadata) {
        val gain = ReplayGainTags.parse(metadata) ?: return
        if (tagGains[mediaId] == gain) return
        tagGains[mediaId] = gain
        applyVolume(deck)
        updateState()
    }

    // Concerts

    /** The chapter's title on the lock screen and in Auto, with the concert as the album. */
    private fun updateChapterMetadata() {
        val exo = active.exo
        val item = exo.currentMediaItem ?: return
        val list = chapters[item.mediaId] ?: return
        val index = ChapterNavigation.index(list, exo.currentPosition / 1000.0)
        val shown = item.mediaId to index
        if (shownChapter == shown) return
        shownChapter = shown
        val track = trackById[item.mediaId] ?: return
        val metadata = item.mediaMetadata.buildUpon()
            .setTitle(index?.let { list[it].title } ?: track.title)
            .setAlbumTitle(if (index != null) track.title else track.album)
            .build()
        // The same address: ExoPlayer updates the metadata without reopening the stream.
        exo.replaceMediaItem(exo.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata).build())
        updateState()
    }

    private fun updateState() {
        val exo = active.exo
        val id = exo.currentMediaItem?.mediaId
        val list = id?.let { chapters[it] }.orEmpty()
        val chapterIndex = if (list.isEmpty()) null else ChapterNavigation.index(list, exo.currentPosition / 1000.0)
        val chapterNext = list.isNotEmpty() && ChapterNavigation.next(list, exo.currentPosition / 1000.0) != null
        _state.value = State(
            currentTrack = queue.currentTrack,
            isPlaying = exo.isPlaying,
            position = queue.currentTrack?.let { queue.positionDisplay },
            listPosition = queue.listPositionDisplay,
            played = queue.played,
            upcoming = queue.upcoming,
            isShuffled = queue.isShuffled,
            repeatMode = repeatMode,
            hasNext = exo.hasNextMediaItem() || chapterNext,
            hasPrevious = exo.hasPreviousMediaItem() || (chapterIndex ?: 0) > 0,
            format = id?.let { formats[it] },
            replayGainDb = gainDb(id),
            isCrossfading = fade != null,
            chapters = list,
            chapterIndex = chapterIndex,
        )
    }

    // Decks

    private inner class Deck(val exo: ExoPlayer) : Player.Listener {
        val player: Player = DeckPlayer(this)
        var fade = 1f
        var effects: DeckEffects? = null
        val idle = IdleDecoderRelease(exo, config.idleReleaseMs)

        init {
            PlayerSetup.configure(exo, ContentKind.MUSIC)
            exo.pauseAtEndOfMediaItems = false
            // The playlist is already in play order (PlayQueue shuffles it), so ExoPlayer's
            // own shuffle must not reorder it again; shuffle mode is on only for controllers.
            exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
            exo.addListener(this)
        }

        private val isActive get() = this === active

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isActive) onTransition(this, mediaItem, reason) else applyVolume(this)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isActive) onPlayingChanged(this, isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isActive) {
                onPlaybackState(this, playbackState)
            } else if (playbackState == Player.STATE_ENDED && this@MusicPlayer.fade?.outgoing === this) {
                finishFade()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isActive && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                onPausedAtEndOfItem(this)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isActive) onError(this, error)
        }

        override fun onTracksChanged(tracks: Tracks) = onFormat(this, tracks)

        override fun onMetadata(metadata: Metadata) {
            exo.currentMediaItem?.mediaId?.let { onTags(this, it, metadata) }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects?.release()
            effects = null
            applyVolume(this)
        }
    }

    /**
     * The deck as the media session sees it: the lock screen, Bluetooth, Android Auto and the
     * app's controller all go through the queue rules here rather than editing ExoPlayer's
     * playlist behind the queue's back.
     */
    private inner class DeckPlayer(private val deck: Deck) : ForwardingPlayer(deck.exo) {
        override fun play() {
            beforePlay(deck)
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) beforePlay(deck)
            super.setPlayWhenReady(playWhenReady)
        }

        override fun seekToNext() = skipToNext()

        override fun seekToPrevious() = skipToPrevious()

        override fun seekToNextMediaItem() {
            cancelFade()
            super.seekToNextMediaItem()
        }

        override fun seekToPreviousMediaItem() {
            cancelFade()
            super.seekToPreviousMediaItem()
        }

        override fun seekTo(positionMs: Long) {
            cancelFade()
            super.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            cancelFade()
            super.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekToDefaultPosition() {
            cancelFade()
            super.seekToDefaultPosition()
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            cancelFade()
            super.seekToDefaultPosition(mediaItemIndex)
        }

        override fun stop() = this@MusicPlayer.stop()

        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = setShuffled(shuffleModeEnabled)

        override fun setRepeatMode(repeatMode: Int) = this@MusicPlayer.setRepeatMode(repeatMode.toRepeatMode())

        override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), 0, 0)

        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)

        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = setMediaItems(listOf(mediaItem), 0, 0)

        override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, 0, 0)

        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = setMediaItems(mediaItems, 0, 0)

        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            val tracks = tracksOf(mediaItems)
            val start = tracks.getOrNull(startIndex.coerceAtLeast(0))?.id
            load(tracks, start, if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs, false, null)
        }

        override fun addMediaItem(mediaItem: MediaItem) = append(tracksOf(listOf(mediaItem)))

        override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, listOf(mediaItem))

        override fun addMediaItems(mediaItems: List<MediaItem>) = append(tracksOf(mediaItems))

        /** The queue knows "next" and "last"; an index in between counts as the end. */
        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
            val tracks = tracksOf(mediaItems)
            if (index <= deck.exo.currentMediaItemIndex + 1) insertNext(tracks) else append(tracks)
        }

        override fun removeMediaItem(index: Int) = removeMediaItems(index, index + 1)

        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
            val first = deck.exo.currentMediaItemIndex + 1
            removeUpcoming((maxOf(fromIndex, first) until toIndex).map { it - first })
        }

        override fun moveMediaItem(currentIndex: Int, newIndex: Int) = moveMediaItems(currentIndex, currentIndex + 1, newIndex)

        override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
            val first = deck.exo.currentMediaItemIndex + 1
            if (fromIndex < first || newIndex < first) return
            // ExoPlayer's newIndex is where the first moved item ends up; the queue wants the
            // gap it is dropped into, counted before the move.
            val gap = if (newIndex > fromIndex) newIndex + (toIndex - fromIndex) else newIndex
            moveUpcoming((fromIndex until toIndex).map { it - first }, gap - first)
        }

        override fun clearMediaItems() = this@MusicPlayer.stop()

        override fun isCommandAvailable(command: Int): Boolean =
            super.isCommandAvailable(command) || (command == COMMAND_SEEK_TO_NEXT && state.value.hasNext)

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            if (!state.value.hasNext || commands.contains(COMMAND_SEEK_TO_NEXT)) return commands
            return commands.buildUpon().add(COMMAND_SEEK_TO_NEXT).build()
        }

        private fun tracksOf(items: List<MediaItem>) = items.map { trackById[it.mediaId] ?: MusicMediaItems.trackOf(it) }
    }

    companion object {
        /** The next song is cached once the current one has this little left. */
        const val PREFETCH_LEAD_MS = 30_000L
        const val TICK_MS = 500L
        const val PROGRESS_INTERVAL_MS = 15_000L

        /** Load failures in a row after which playback stops instead of skipping on. */
        const val MAX_FAILURES = 3
    }
}

internal fun RepeatMode.toPlayer(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

internal fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    else -> RepeatMode.OFF
}
