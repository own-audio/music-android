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
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

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
        jellyfinInfo = audio.own.kit.jellyfin.ClientInfo(
            client = ServerDetector.CLIENT_NAME,
            device = device.name,
            deviceId = jellyfinDeviceId(),
            version = versionName,
            userAgent = userAgent,
        ),
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
        PlaybackStack(
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
    }
    val playback: PlaybackStack by playbackLazy

    val engine: StackEngine by lazy { StackEngine(playback) }
    val playbackModel: PlaybackModel by lazy { PlaybackModel(engine) }

    /** The header of a shared log: which app, which system. */
    val logHeader: String get() = "own.music $versionName ($versionCode), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}

/** Offline at all; [byChoice] when Offline Mode or "Downloaded Only" on mobile data made it so. */
data class OfflineState(val isOffline: Boolean = false, val byChoice: Boolean = false, val offlineMode: Boolean = false)
