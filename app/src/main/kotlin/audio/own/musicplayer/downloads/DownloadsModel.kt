package audio.own.musicplayer.downloads

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import audio.own.core.downloads.DownloadCollection
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadFetcher
import audio.own.core.downloads.DownloadNotifications
import audio.own.core.downloads.DownloadRequest
import audio.own.core.downloads.DownloadWorkerFactory
import audio.own.core.downloads.Downloads
import audio.own.core.downloads.DownloadsDatabase
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.core.downloads.WorkManagerScheduler
import audio.own.core.servers.CompositeId
import audio.own.kit.ownaudio.HoldingRef
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.updateHoldings
import audio.own.music.library.MusicSource
import audio.own.music.library.SourceError
import audio.own.music.library.model.Album
import audio.own.music.library.model.Playlist
import audio.own.music.library.model.Song
import audio.own.music.library.ownaudio.OwnAudioSource
import audio.own.musicplayer.R
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.settings.PlaybackSettings
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What a download is for the screens: its song, from the index's record. */
data class DownloadedSong(val entity: DownloadEntity, val song: Song) {
    val key: String get() = entity.key
    val serverId: String get() = entity.serverId
}

/**
 * Songs kept on the phone (`:core:downloads`), as the music screens use them: albums, playlists
 * and songs, each with its whole record, the player's local files, and what the hosted service is
 * told the phone keeps (`PUT /me/holdings`).
 */
class DownloadsModel(
    context: Context,
    private val sourceOf: (String) -> MusicSource?,
    private val sourceForServer: (String) -> MusicSource?,
    /** Waits until the saved servers are read: a worker can start before any screen. */
    private val serversLoaded: suspend () -> Unit,
    bareHttp: OkHttpClient,
    userAgent: String,
    proxyMemory: ProxyMemory,
    private val settings: () -> PlaybackSettings,
    settingsChanges: StateFlow<PlaybackSettings>,
    /** The song playing now, never evicted. */
    private val playing: () -> String?,
    private val log: AppLog,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext

    val downloads = Downloads(
        db = DownloadsDatabase.open(app),
        root = File(app.filesDir, "downloads"),
        scheduler = WorkManagerScheduler { WorkManager.getInstance(app) },
        resolver = { item -> link(item) },
        fetcher = DownloadFetcher(bareHttp, proxyMemory, userAgent),
        settings = object : Downloads.Settings {
            override fun wifiOnly() = settings().downloadsWifiOnly
            override fun capBytes() = settings().keepAtMostBytes
            override fun protectedKey() = playing()
        },
        scope = scope,
        onHoldingsChanged = ::reportHoldings,
        isTransient = ::isTransient,
        log = { log.info("downloads", it) },
    )

    val workerFactory = DownloadWorkerFactory(
        downloads = { downloads },
        notifications = DownloadNotifications(
            channelName = { it.getString(R.string.downloads) },
            text = { ctx, title -> if (title != null) ctx.getString(R.string.downloading_title, title) else ctx.getString(R.string.downloading_music) },
            smallIcon = R.drawable.ic_stat_download,
        ),
    )

    val state: StateFlow<DownloadsSnapshot> get() = downloads.state
    val progress: StateFlow<Map<String, Float>> get() = downloads.progress

    init {
        scope.launch {
            settingsChanges.map { it.downloadsWifiOnly }.distinctUntilChanged().drop(1).collect { downloads.wifiOnlyChanged() }
        }
        scope.launch {
            settingsChanges.map { it.keepAtMostGb }.distinctUntilChanged().drop(1).collect { downloads.enforceCap() }
        }
    }

    // region Reading

    /** The player's local file for a song, or null to stream it. Any thread. */
    fun localFile(trackId: String): Uri? = downloads.file(trackId)?.takeIf { it.isFile }?.let(Uri::fromFile)

    fun isDownloaded(trackId: String): Boolean = downloads.file(trackId) != null


    // endregion

    // region Changing

    suspend fun download(album: Album, songs: List<Song>) {
        val serverId = serverOf(album.id) ?: return
        downloads.download(
            songs.map(::request),
            DownloadCollection(
                id = albumCollection(album.id), kind = ALBUM, serverId = serverId, itemId = album.id, name = album.name,
                subtitle = album.artist, coverId = album.coverArt ?: album.id,
                record = recordJson.encodeToString(CollectionRecord.serializer(), CollectionRecord.of(album)),
            ),
        )
    }

    suspend fun download(playlist: Playlist, songs: List<Song>) {
        val serverId = serverOf(playlist.id) ?: return
        downloads.download(
            songs.map(::request),
            DownloadCollection(
                id = playlistCollection(playlist.id), kind = PLAYLIST, serverId = serverId, itemId = playlist.id, name = playlist.name,
                coverId = playlist.coverArt, record = recordJson.encodeToString(CollectionRecord.serializer(), CollectionRecord.of(playlist)),
            ),
        )
    }

    suspend fun download(song: Song) {
        downloads.download(listOf(request(song)))
    }

    suspend fun remove(trackId: String) = downloads.remove(trackId)
    suspend fun removeCollection(id: String) = downloads.removeCollection(id)
    suspend fun removeAll(serverId: String? = null) = downloads.removeAll(serverId)
    suspend fun retryFailed() = downloads.retryFailed()
    suspend fun notePlayed(trackId: String) = downloads.notePlayed(trackId)

    /** At launch: what the index doesn't know goes, what was waiting starts again. */
    suspend fun reconcile() = downloads.reconcile()

    private fun request(song: Song): DownloadRequest = DownloadRequest(
        itemId = song.id,
        serverId = serverOf(song.id).orEmpty(),
        record = recordJson.encodeToString(SongRecord.serializer(), SongRecord.of(song)),
        extensionHint = song.suffix,
        title = song.title,
    )

    // endregion

    /** The original, whatever its format: a download is kept as the server has it. */
    private suspend fun link(item: DownloadEntity): PlaybackLink {
        serversLoaded()
        val source = sourceOf(item.itemId) ?: throw SourceError.NotFound()
        val link = source.downloadLink(item.itemId)
        return PlaybackLink(link.url, link.expiresAt?.toEpochMilli(), link.contentType)
    }

    /** What each own.audio server is told this phone keeps: the whole set, after changes. */
    private suspend fun reportHoldings(byServer: Map<String, List<String>>) {
        serversLoaded()
        for ((serverId, ids) in byServer) {
            val source = sourceForServer(serverId) as? OwnAudioSource ?: continue
            if (source.client.tokens?.current == null) continue
            try {
                source.client.updateHoldings(items = ids.mapNotNull { id -> CompositeId.split(id)?.second?.let { HoldingRef("track", it) } })
            } catch (e: OwnAudioException) {
                log.error("downloads", "holdings not reported: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is IOException, is SourceError.Network, is SourceError.Offline, is OwnAudioException.Network, is OwnAudioException.Offline -> true
        is SourceError.Http -> error.status >= 500 || error.status == 429
        else -> false
    }

    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        fun albumCollection(albumId: String) = "$ALBUM:$albumId"
        fun playlistCollection(playlistId: String) = "$PLAYLIST:$playlistId"
        fun serverOf(id: String): String? = CompositeId.split(id)?.first
    }
}
