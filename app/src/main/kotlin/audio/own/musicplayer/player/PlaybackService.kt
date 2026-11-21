package audio.own.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import audio.own.musicplayer.MainActivity
import audio.own.musicplayer.OwnAudioApp
import audio.own.musicplayer.library.Covers
import audio.own.player.core.ArtworkBitmapLoader
import audio.own.player.core.PlaybackSessionCallback
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The media service: one library session over the music engine, for the notification, the lock
 * screen, Bluetooth and headset buttons, and (from F8) Android Auto. The engine itself lives in
 * the app's container, so the screens and the session share one player.
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
        val built = MediaLibrarySession.Builder(this, stack.music.player, Callback(scope, stack))
            .setBitmapLoader(CacheBitmapLoader(artwork))
            .setSessionActivity(open)
            .build()
        session = built
        stack.onActivePlayerChanged = { built.player = it }
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

    /**
     * Resumption from the last queue, and an empty browse root until Android Auto's tree comes
     * (F8): older media browsers connect through the root and give up without one.
     */
    private class Callback(scope: CoroutineScope, private val stack: PlaybackStack) : PlaybackSessionCallback(scope) {
        override suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? = stack.music.resumptionItems()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(ROOT, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }

    private companion object {
        val ROOT: MediaItem = MediaItem.Builder()
            .setMediaId("root")
            .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
            .build()
    }
}
