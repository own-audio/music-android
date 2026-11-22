package audio.own.musicplayer.player

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import audio.own.core.servers.NetworkState
import audio.own.core.servers.OfflineSwitch
import audio.own.music.library.MusicSource
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.library.Covers
import audio.own.musicplayer.settings.PlaybackSettingsStore
import audio.own.player.core.ContentKind
import audio.own.player.core.DiskResumeStore
import audio.own.player.core.ListeningCache
import audio.own.player.core.PlaybackDataSources
import audio.own.player.core.PlayerSetup
import audio.own.player.core.ProxyMemory
import audio.own.player.music.MusicMediaItems
import audio.own.player.music.MusicPlayer
import audio.own.player.music.ScrobbleTracker
import audio.own.player.music.lastQueueStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Everything playback needs, built once per process on the main thread: the listening cache, the
 * data source chain over the link resolver, and the music engine with the listener's settings.
 * The media service puts a session on it; the screens drive it through [PlaybackModel].
 */
@UnstableApi
class PlaybackStack(
    context: Context,
    private val sourceOf: (String) -> MusicSource?,
    bareHttp: OkHttpClient,
    userAgent: String,
    private val settings: PlaybackSettingsStore,
    private val network: StateFlow<NetworkState>,
    private val offline: OfflineSwitch,
    private val offlineChanges: Flow<Boolean>,
    private val log: AppLog,
    /** The storage route memory, shared with the downloads. */
    val proxyMemory: ProxyMemory,
    /** A downloaded song's file, which plays instead of a stream. Asked on the player's thread. */
    private val localFile: (String) -> Uri? = { null },
    /** A counted play, for the outbox (stored first, sent when the server can be reached). */
    private val recordPlay: (suspend (trackId: String, startedAtMs: Long, heardMs: Long) -> Unit)? = null,
    /** A song started: a downloaded one moves to the back of the line for Keep at Most. */
    private val onTrackStarted: (String) -> Unit = {},
) {
    private val app = context.applicationContext
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dataSources = PlaybackDataSources(
        context = app,
        resolver = AppLinkResolver(sourceOf),
        httpClient = bareHttp,
        cache = ListeningCache.open(app),
        proxyMemory = proxyMemory,
        userAgent = userAgent,
    )

    val music = MusicPlayer(
        MusicPlayer.Config(
            newDeck = { PlayerSetup.buildPlayer(app, dataSources.mediaSourceFactory(), ContentKind.MUSIC) },
            items = MusicMediaItems(
                localFile = localFile,
                artworkUri = { track -> track.artworkId?.let(Covers::artworkUri) },
            ),
            isFullyCached = dataSources::isFullyCached,
            prefetchSource = dataSources.cacheDataSourceFactory,
            maxBitrateKbps = { settings.current.maxBitrateKbps(network.value) },
            isOffline = { offline.isOffline },
            // On mobile data only what is playing is fetched.
            canPrefetch = { network.value.connected && !network.value.metered },
            lastQueue = lastQueueStore(File(app.filesDir, "last-queue.json")),
            resumeStore = DiskResumeStore(File(app.filesDir, "resume.json")),
        ),
    )

    /** The last load failure, until the next song starts. */
    private val _error = MutableStateFlow<PlaybackException?>(null)
    val error: StateFlow<PlaybackException?> = _error.asStateFlow()

    private val reporter = PlayReporter(
        sourceOf = sourceOf,
        enabled = { settings.current.reportPlays },
        scope = io,
        recordPlay = recordPlay,
        onError = { log.error("plays", it) },
    )

    /** Concerts: their chapters, resume point and saved position (own.audio). */
    val concerts = ConcertTracker(
        sourceOf = sourceOf,
        isOffline = { offline.isOffline },
        scope = io,
        onPlayer = { work -> main.launch { work() } },
        setChapters = music::setChapters,
        seek = { id, ms -> if (music.state.value.currentTrack?.id == id) music.player.seekTo(ms) },
    )

    /** The service's session follows the active deck: set by the service while it runs. */
    var onActivePlayerChanged: ((Player) -> Unit)? = null

    init {
        music.listener = object : MusicPlayer.Listener {
            override fun onActivePlayerChanged(player: Player) {
                onActivePlayerChanged?.invoke(player)
            }

            override fun onScrobble(event: ScrobbleTracker.Event) {
                when (event) {
                    is ScrobbleTracker.Event.NowPlaying -> reporter.started(event.key, music.player.currentPosition)
                    is ScrobbleTracker.Event.Played -> reporter.played(event.key, event.startedAtMs, (event.heardSecs * 1000).toLong())
                }
            }

            override fun onProgress(trackId: String, positionMs: Long, isPlaying: Boolean) {
                reporter.progress(trackId, positionMs, isPlaying)
                concerts.progress(trackId, positionMs)
            }

            override fun onPlaybackError(error: PlaybackException, stopped: Boolean) {
                log.error("player", "${error.errorCodeName}: ${error.cause?.message ?: error.message}${if (stopped) " (stopped)" else ""}")
                _error.value = error
            }
        }
        main.launch { music.state.collect { if (it.isPlaying) _error.value = null } }
        main.launch { music.state.map { it.currentTrack?.id }.distinctUntilChanged().collect { id -> id?.let(onTrackStarted) } }
        main.launch {
            music.state.map { it.currentTrack }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { track ->
                if (track != null) concerts.started(track.id, music.player.currentPosition, track.durationSecs?.let { it * 1000L })
            }
        }
        // A pause reported "stopped"; playing on is a new start for the server. No track at all: the end.
        main.launch {
            music.state.map { it.currentTrack?.id to it.isPlaying }.distinctUntilChanged().collect { (id, playing) ->
                if (id == null) reporter.ended() else if (playing) reporter.resumed(id, music.player.currentPosition)
            }
        }
        main.launch {
            settings.settings.collect { s ->
                music.setCrossfade(s.crossfadeSecs)
                music.setReplayGainMode(s.replayGain)
            }
        }
        main.launch { settings.equalizer.settings.collect(music::setEqualizer) }
        // A new cap or going offline rebuilds what is still to come; the song playing stays.
        main.launch {
            combine(settings.settings, network, offlineChanges) { s, n, off -> s.maxBitrateKbps(n) to off }
                .distinctUntilChanged()
                .drop(1)
                .collect { music.refreshUpcoming() }
        }
        main.launch { network.collect { proxyMemory.network = it.signature } }
        music.restoreLastQueue()
    }

    fun clearError() {
        _error.value = null
    }
}

/** The storage-route memory between launches, in its own small preferences file. */
class PreferencesProxyStore(private val prefs: SharedPreferences) : ProxyMemory.Store {
    private val serializer = MapSerializer(String.serializer(), Long.serializer())

    override fun load(): Map<String, Long> =
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    override fun save(entries: Map<String, Long>) {
        prefs.edit { putString(KEY, Json.encodeToString(serializer, entries)) }
    }

    private companion object {
        const val KEY = "entries"
    }
}
