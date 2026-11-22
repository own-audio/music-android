package audio.own.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.music.library.MusicSource
import audio.own.musicplayer.AppContainer
import audio.own.musicplayer.MainActivity
import audio.own.musicplayer.OwnAudioApp
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import audio.own.musicplayer.auto.AutoContent
import audio.own.musicplayer.auto.AutoLibrary
import audio.own.musicplayer.auto.MusicSessionPlayer
import audio.own.musicplayer.library.Covers
import audio.own.player.core.ArtworkBitmapLoader
import audio.own.player.core.PlaybackSessionCallback
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import coil3.SingletonImageLoader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

/**
 * The media service: one library session over the music engine, for the notification, the lock
 * screen, Bluetooth and headset buttons, the assistant and Android Auto. The engine itself lives
 * in the app's container, so the screens and the session share one player.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as OwnAudioApp).container
        val stack = container.playback
        val loader = SingletonImageLoader.get(this)
        val artwork = ArtworkBitmapLoader(scope) { uri ->
            Covers.coverIdOf(uri)?.let { Covers.bitmap(this, loader, it) }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val auto = AutoLibrary(
            library = object : AutoLibrary.Library {
                override val hasServer: Boolean get() = container.servers.state.value.activeId != null
                override fun source(): MusicSource? = container.browsingSource()
                override val downloads: DownloadsSnapshot get() = container.downloads.state.value
            },
            words = AutoWords(),
            artwork = container.carArtwork::uri,
        )
        val built = MediaLibrarySession.Builder(this, MusicSessionPlayer(stack.music.player), Callback(scope, container, auto))
            .setBitmapLoader(CacheBitmapLoader(artwork))
            .setSessionActivity(open)
            .build()
        session = built
        stack.onActivePlayerChanged = { built.player = MusicSessionPlayer(it) }
        // The car's lists follow the server chosen on the phone and what is downloaded.
        scope.launch {
            container.servers.state.map { it.activeId to it.servers.size }.distinctUntilChanged().drop(1).collect {
                built.notifyChildrenChanged(AutoContent.ROOT, Int.MAX_VALUE, null)
                for (entry in AutoContent.RootEntry.entries) built.notifyChildrenChanged(entry.id, Int.MAX_VALUE, null)
            }
        }
        scope.launch {
            container.downloads.state.map { AutoContent.downloadRows(it) }.distinctUntilChanged().drop(1).collect {
                built.notifyChildrenChanged(AutoContent.RootEntry.DOWNLOADS.id, Int.MAX_VALUE, null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        val container = (application as OwnAudioApp).container
        container.playback.onActivePlayerChanged = null
        container.playback.music.onBackground()
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class AutoWords : AutoLibrary.Words {
        override fun title(entry: AutoContent.RootEntry): String = getString(
            when (entry) {
                AutoContent.RootEntry.RECENTLY_PLAYED -> R.string.recently_played
                AutoContent.RootEntry.RECENTLY_ADDED -> R.string.recently_added
                AutoContent.RootEntry.DOWNLOADS -> R.string.downloads
                AutoContent.RootEntry.PLAYLISTS -> R.string.playlists
                AutoContent.RootEntry.ARTISTS -> R.string.artists
                AutoContent.RootEntry.FAVORITES -> R.string.favorites
            },
        )

        override val more: String get() = getString(SR.string.more)
        override val addServer: String get() = getString(R.string.auto_add_server)
        override val nothingHere: String get() = getString(R.string.auto_nothing_here)
        override val noPlaylists: String get() = getString(R.string.no_playlists)
        override val noArtists: String get() = getString(R.string.no_artists)
        override val noAlbums: String get() = getString(R.string.no_albums)
        override val noFavorites: String get() = getString(R.string.no_favorites)
        override val nothingDownloaded: String get() = getString(R.string.nothing_downloaded)
        override val favoriteSongs: String get() = getString(R.string.favorite_songs)
        override val downloadedSongs: String get() = getString(R.string.downloaded_songs)
        override val albums: String get() = getString(R.string.albums)
        override val songs: String get() = getString(R.string.songs)
        override val artists: String get() = getString(R.string.artists)
        override val playlists: String get() = getString(R.string.playlists)

        override fun songCount(count: Int): String = resources.getQuantityString(R.plurals.songs_count, count, count)

        override fun failure(error: Throwable): String = error.userMessage().resolve(this@PlaybackService)
    }

    /**
     * Resumption from the last queue, the browse tree for Android Auto and other media browsers,
     * and what a controller picks or asks for by voice.
     */
    private class Callback(
        private val scope: CoroutineScope,
        private val container: AppContainer,
        private val auto: AutoLibrary,
    ) : PlaybackSessionCallback(scope) {
        private val stack get() = container.playback
        private val searches = ConcurrentHashMap<String, List<MediaItem>>()

        override suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? = stack.music.resumptionItems()

        private suspend fun ready() = container.servers.state.first { it.loaded }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            params?.extras?.let { auto.tabLimit = it.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, Int.MAX_VALUE) }
            val extras = Bundle().apply {
                putBoolean(SEARCH_SUPPORTED, true)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(auto.root(), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            ready()
            LibraryResult.ofItemList(paged(auto.children(parentId), page, pageSize), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = auto.item(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                ready()
                val found = auto.search(query)
                searches[query] = found
                session.notifySearchResultChanged(browser, query, found.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            ready()
            val found = searches[query] ?: auto.search(query).also { searches[query] = it }
            LibraryResult.ofItemList(paged(found, page, pageSize), params)
        }

        /**
         * A controller picked a row in the car, or asked by voice ("play ‹album› on own.music",
         * which arrives as a search query with no id). The app queues the songs; the session gets
         * back what the player then holds. Items from anywhere else go the usual way.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val picked = mediaItems.getOrNull(startIndex.takeIf { it in mediaItems.indices } ?: 0)
            val id = picked?.mediaId.orEmpty()
            if (id.isNotEmpty() && !auto.knows(id)) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    ready()
                    val requests = container.playRequests
                    val problem = if (id.isEmpty()) {
                        requests.playFromSearch(picked?.requestMetadata?.searchQuery)
                    } else {
                        val playable = auto.playable(id)
                        if (playable == null) UiText(R.string.cant_play_now) else requests.playSongs(playable.songs, playable.startId, playable.containerId)
                    }
                    val player = mediaSession.player
                    if (problem != null || player.mediaItemCount == 0) {
                        container.log.info("auto", "a request from ${controller.packageName} didn't play")
                        result.setException(UnsupportedOperationException("Nothing to play"))
                    } else {
                        result.set(MediaSession.MediaItemsWithStartPosition(MusicSessionPlayer.loadedItems(player), player.currentMediaItemIndex, C.TIME_UNSET))
                    }
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        private fun <T : Any> future(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val result = SettableFuture.create<LibraryResult<T>>()
            scope.launch {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }
    }

    private companion object {
        /** The car shows its search button only for a root that says it searches. */
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
            return ImmutableList.copyOf(items.drop(page * pageSize).take(pageSize))
        }
    }
}
