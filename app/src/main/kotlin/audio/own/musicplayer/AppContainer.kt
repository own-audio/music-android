package audio.own.musicplayer

import android.content.Context
import androidx.core.content.edit
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import audio.own.core.servers.DiskAnswerCache
import audio.own.core.servers.KeystoreSecretBox
import audio.own.core.servers.NetworkMonitor
import audio.own.core.servers.OfflineSwitch
import audio.own.core.servers.PreferencesAddressMemory
import audio.own.core.servers.ServerStore
import audio.own.kit.ownaudio.AnswerCache
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSessions
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.AppPreferences
import audio.own.musicplayer.app.DataStorePreferences
import audio.own.musicplayer.app.LoggingInterceptor
import audio.own.musicplayer.library.Covers
import audio.own.musicplayer.library.LibraryModel
import audio.own.musicplayer.library.SourceRegistry
import audio.own.musicplayer.player.PlaybackModel
import audio.own.musicplayer.player.PlaybackStack
import audio.own.musicplayer.player.PreferencesProxyStore
import audio.own.musicplayer.downloads.DownloadsModel
import audio.own.musicplayer.sync.AppSync
import audio.own.music.library.FavoriteKind
import audio.own.player.core.ProxyMemory
import androidx.work.DelegatingWorkerFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import audio.own.musicplayer.player.StackEngine
import audio.own.musicplayer.servers.ServerDetector
import audio.own.musicplayer.servers.ServersModel
import audio.own.musicplayer.apple.AppleSignIn
import audio.own.musicplayer.apple.FilePendingAppleStore
import audio.own.kit.ownaudio.AppleWeb
import audio.own.musicplayer.settings.PlaybackSettingsStore
import audio.own.music.library.db.CatalogueDatabase
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import audio.own.core.servers.CompositeId
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Song
import audio.own.musicplayer.auto.CarArtwork
import audio.own.musicplayer.downloads.OfflineAwareSource
import audio.own.musicplayer.downloads.OfflineLibrary
import audio.own.musicplayer.platform.PlayRequests
import audio.own.musicplayer.widget.PlayingKey
import audio.own.musicplayer.widget.WidgetData
import audio.own.musicplayer.widget.WidgetPlayer
import audio.own.musicplayer.widget.WidgetRedraw
import audio.own.musicplayer.widget.WidgetSnapshot
import audio.own.musicplayer.widget.WidgetStore
import audio.own.musicplayer.widget.WidgetUpdater
import audio.own.player.music.Track
import audio.own.player.music.lastQueueStore

/**
 * Hand-wired dependencies. Android Auto, the assistant and the widget can bind to the media
 * service without any screen having started, so the playback dependencies will be created here,
 * eagerly, rather than from the UI.
 */
@UnstableApi
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log = AppLog(File(app.filesDir, "logs"))

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** The hosted service's edge refuses requests without a proper User-Agent. */
    val userAgent = "own.audio Music/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor(log))
        .build()

    /**
     * For stream links, storage redirects and covers: never an Authorization header, so a token
     * can't reach a storage host (docs/android-foundation.md §7.2).
     */
    val bareHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()

    val preferences: AppPreferences = DataStorePreferences(app)
    val playbackSettings = PlaybackSettingsStore(PlaybackSettingsStore.Shared(app.getSharedPreferences("playback", Context.MODE_PRIVATE)))
    val network = NetworkMonitor(app).also { it.start() }

    /** Whether a screen of the app is started; [OwnAudioApp] keeps it from the process lifecycle. */
    val foreground = MutableStateFlow(false)
    val offline = OfflineSwitch()

    /** [offline] as screens watch it: offline at all, and whether by the listener's choice. */
    private val _offlineState = MutableStateFlow(OfflineState())
    val offlineState: StateFlow<OfflineState> = _offlineState.asStateFlow()

    fun updateOffline() {
        val s = playbackSettings.current
        offline.update(s.offlineMode, network.state.value, s.mobileData)
        _offlineState.value = OfflineState(offline.isOffline, offline.isOfflineByChoice, s.offlineMode)
    }
    val answers = DiskAnswerCache(File(app.cacheDir, "server-responses"))
    val tokenSessions = TokenSessions()
    val serverStore = ServerStore(File(app.filesDir, "servers.bin"), KeystoreSecretBox("own.music.servers")) { log.error("servers", it) }

    /** What the session list on the server shows for this device. */
    val device = OwnAudioClient.Device(
        name = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
        kind = "android",
    )

    val servers = ServersModel(
        store = serverStore,
        sessions = tokenSessions,
        http = http,
        userAgent = userAgent,
        device = device,
        preferences = preferences,
        detector = ServerDetector(http, userAgent, log),
        addressMemory = PreferencesAddressMemory(app.getSharedPreferences("addresses", Context.MODE_PRIVATE)),
        network = network.state,
        cache = object : AnswerCache {
            override fun get(key: String) = answers.get(key)
            override fun put(key: String, bytes: ByteArray) = answers.put(key, bytes)
        },
        clearCache = { answers.clear() },
        isOffline = { offline.isOffline },
        acceptLanguage = { appLocale().toLanguageTag() },
        log = log,
        scope = scope,
        foreground = foreground,
        jellyfinInfo = audio.own.kit.jellyfin.ClientInfo(
            client = ServerDetector.CLIENT_NAME,
            device = device.name,
            deviceId = jellyfinDeviceId(),
            version = versionName,
            userAgent = userAgent,
        ),
    )

    /** Sign in with Apple through the browser; a sign-in waiting for it outlives the process. */
    val appleSignIn = AppleSignIn(
        servers = servers,
        store = FilePendingAppleStore(File(app.noBackupFilesDir, "apple-sign-in.json")),
        redirectUri = AppleWeb.redirectUri(app.packageName),
        log = log,
        scope = scope,
    )

    /**
     * Jellyfin lists sessions by device id; a new one on every launch would leave a trail of
     * devices in its dashboard. Made once and kept.
     */
    private fun jellyfinDeviceId(): String {
        val prefs = app.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit { putString("deviceId", id) }
        return id
    }

    /** Every server's catalogue; opened on first use, off the main thread. */
    val catalogue: CatalogueDatabase by lazy { CatalogueDatabase.open(app) }

    val sources = SourceRegistry(servers, { catalogue }, answers, log, scope)

    val library = LibraryModel(
        servers = servers,
        registry = sources,
        isOffline = { offline.isOffline },
        canSyncNow = { !network.state.value.dataSaver },
        log = log,
        scope = scope,
        onUnmeteredNetwork = { network.state.value.let { it.connected && !it.metered } },
        catalogueDb = { catalogue },
    )

    fun imageLoader(): ImageLoader = Covers.imageLoader(app, sources::sourceOf, bareHttp)

    /** Where own.audio storage is blocked on this network: for streams and downloads alike. */
    val proxyMemory = ProxyMemory(PreferencesProxyStore(app.getSharedPreferences("storage-route", Context.MODE_PRIVATE)))

    val downloads: DownloadsModel = DownloadsModel(
        context = app,
        sourceOf = sources::sourceOf,
        sourceForServer = sources::source,
        serversLoaded = { servers.state.first { it.loaded } },
        bareHttp = bareHttp,
        userAgent = userAgent,
        proxyMemory = proxyMemory,
        settings = { playbackSettings.current },
        settingsChanges = playbackSettings.settings,
        playing = { if (playbackLazy.isInitialized()) playback.music.state.value.currentTrack?.id else null },
        log = log,
        scope = scope,
    )

    val sync: AppSync = AppSync(
        context = app,
        servers = servers,
        sourceForServer = sources::source,
        library = library,
        isOffline = { offline.isOffline },
        network = network.state,
        log = log,
        scope = scope,
        refreshWidget = { widgets.refreshAlbums() },
    ).also { sync ->
        library.offlineChanges = object : LibraryModel.OfflineChanges {
            override suspend fun favorite(kind: FavoriteKind, id: String, on: Boolean) = sync.recordFavorite(kind, id, on)
            override suspend fun rating(kind: FavoriteKind, id: String, rating: Int) = sync.recordRating(kind, id, rating)
        }
    }

    /** WorkManager's workers, with their dependencies: downloads and the periodic refresh. */
    val workerFactory = DelegatingWorkerFactory().apply {
        addFactory(downloads.workerFactory)
        addFactory(sync.refreshWorkerFactory)
    }

    /**
     * The player and its caches. Built on the first touch, on the main thread: by the media
     * service when Bluetooth, the system's controls or Auto bind it with no screen open, or by
     * the first screen.
     */
    private val playbackLazy: Lazy<PlaybackStack> = lazy {
        val stack = PlaybackStack(
            context = app,
            sourceOf = sources::sourceOf,
            bareHttp = bareHttp,
            userAgent = userAgent,
            settings = playbackSettings,
            network = network.state,
            offline = offline,
            offlineChanges = offlineState.map { it.isOffline },
            log = log,
            proxyMemory = proxyMemory,
            localFile = downloads::localFile,
            recordPlay = { id, started, heard -> sync.recordPlay(id, started, heard) },
            onTrackStarted = { id -> if (downloads.isDownloaded(id)) scope.launch { downloads.notePlayed(id) } },
        )
        scope.launch {
            stack.music.state.collect { state ->
                widgetTrack.value = state.currentTrack
                widgetPlaying.value = state.currentTrack?.let { PlayingKey(it.id, state.isPlaying) }
            }
        }
        stack
    }
    val playback: PlaybackStack by playbackLazy

    val engine: StackEngine by lazy { StackEngine(playback) }
    val playbackModel: PlaybackModel by lazy { PlaybackModel(engine) { songs -> playback.concerts.remember(songs) } }

    /** Sharing, pictures and identify of the listener's own songs (own.audio). */
    val ownAudio = audio.own.musicplayer.library.OwnAudioActions(
        sourceOf = sources::sourceOf,
        sourceForServer = sources::source,
        isOffline = { offline.isOffline },
        syncServer = { id -> library.syncServer(id, force = true) },
        forgetCovers = { ids -> audio.own.musicplayer.library.Covers.forget(app, ids) },
        scope = scope,
    )

    /**
     * The active server's source as the library screens read it: offline, the downloads (or the
     * catalogue on the phone with Whole Library When Offline). For Android Auto, the assistant
     * and the widget's links.
     */
    fun browsingSource(): MusicSource? {
        val source = library.activeSource ?: return null
        val offlineLibrary = OfflineLibrary({ downloads.state.value }, source.serverId)
        return OfflineAwareSource(source, offlineLibrary, { offline.isOffline }, { playbackSettings.current.offlineWholeLibrary })
    }

    /** Android Auto's row pictures. */
    val carArtwork = CarArtwork("${app.packageName}.artwork", File(app.cacheDir, "car-artwork"))

    /** The widget's links, the launcher's shortcut and the assistant. Main thread. */
    val playRequests: PlayRequests by lazy {
        PlayRequests(
            serversLoaded = { servers.state.first { it.loaded } },
            activeServerId = { servers.state.value.activeId },
            source = ::browsingSource,
            player = object : PlayRequests.Player {
                override val hasTrack: Boolean get() = playbackModel.state.value.currentTrack != null
                override fun resume() {
                    if (!playbackModel.state.value.isPlaying) playbackModel.togglePlayPause()
                }
                override fun play(songs: List<Song>, startId: String?, containerId: String?) = playbackModel.play(songs, startId, false, containerId)
            },
            isOffline = { offline.isOffline },
            isDownloaded = { downloads.state.value.isDownloaded(it) },
        )
    }

    /**
     * The song the widget's first entry follows: the player's once it is built, until then the
     * last queue's, read from its file so the widget needs no player.
     */
    private val widgetTrack = MutableStateFlow<Track?>(null)
    private val widgetPlaying = MutableStateFlow<PlayingKey?>(null)

    val widgets = WidgetUpdater(
        store = WidgetStore(WidgetData.folder(app)),
        track = widgetTrack,
        activeServerId = servers.state.map { if (it.loaded) it.activeId else null },
        source = { library.activeSource },
        isOffline = { offline.isOffline },
        jpeg = { coverId -> CarArtwork.smallJpeg(app, coverId, WIDGET_COVER_PX) },
        redraw = { WidgetRedraw.all(app) },
        playing = widgetPlaying,
        redrawPlayers = { WidgetRedraw.players(app) },
        log = log,
    )

    /**
     * The song in the player for the widgets, with where it is; null when no player is built (the
     * widgets then offer where you left off), so drawing a widget never builds one.
     */
    suspend fun widgetPlayer(): WidgetPlayer? {
        if (!playbackLazy.isInitialized()) return null
        return withContext(Dispatchers.Main) {
            val state = playback.music.state.value
            val track = state.currentTrack ?: return@withContext null
            val player = playback.music.player
            WidgetPlayer(
                title = track.title,
                artist = track.artist.orEmpty(),
                cover = track.artworkId?.let(WidgetSnapshot::coverName),
                isPlaying = state.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it > 0 } ?: ((track.durationSecs ?: 0) * 1000L),
            )
        }
    }

    fun startWidget() {
        scope.launch(Dispatchers.IO) {
            val last = lastQueueStore(File(app.filesDir, "last-queue.json")).read()
            if (!playbackLazy.isInitialized()) widgetTrack.compareAndSet(null, last?.tracks?.firstOrNull { it.id == last.currentTrackId })
        }
        widgets.start(scope)
    }

    /**
     * A server was removed: its song stops if it is playing. With no player built yet, a last
     * queue that would come back with its songs is forgotten instead.
     */
    suspend fun stopMusicOf(serverId: String) {
        if (playbackLazy.isInitialized()) {
            withContext(Dispatchers.Main) { playbackModel.serverRemoved(serverId) }
        } else {
            withContext(Dispatchers.IO) {
                val store = lastQueueStore(File(app.filesDir, "last-queue.json"))
                val current = store.read()?.currentTrackId
                if (current != null && CompositeId.split(current)?.first == serverId) store.delete()
            }
        }
    }

    /** The header of a shared log: which app, which system. */
    val logHeader: String get() = "own.music $versionName ($versionCode), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}

/** The widgets' pictures: the big player's cover is about 150 dp across. */
private const val WIDGET_COVER_PX = 320

/** Offline at all; [byChoice] when Offline Mode or "Downloaded Only" on mobile data made it so. */
data class OfflineState(val isOffline: Boolean = false, val byChoice: Boolean = false, val offlineMode: Boolean = false)
