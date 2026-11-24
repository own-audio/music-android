package audio.own.musicplayer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.work.Configuration
import androidx.work.WorkManager
import audio.own.core.sync.PeriodicRefresh
import audio.own.musicplayer.apple.AppleRedirects
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@UnstableApi
class OwnAudioApp : Application(), SingletonImageLoader.Factory, Configuration.Provider, AppleRedirects {
    lateinit var container: AppContainer
        private set

    override fun appleRedirected(code: String?, error: String?, state: String?) = container.appleSignIn.redirected(code, error, state)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A locked screen, the background and playing with the phone locked all stop the
        // activity: what only the listener looking at the app needs stops with it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.foreground.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                container.foreground.value = false
            }
        })
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch {
            container.servers.load()
            container.library.syncActive()
            container.sync.flush()
        }
        // What the index doesn't know goes; what waited (killed mid-download) starts again.
        container.scope.launch { container.downloads.reconcile() }
        container.sync.start()
        container.startWidget()
        container.scope.launch { audio.own.musicplayer.widget.WidgetPreviews.publish(this@OwnAudioApp, container.versionCode) }
        PeriodicRefresh.schedule(WorkManager.getInstance(this))
        // Offline Mode, "Downloaded Only" on mobile data, or no network at all.
        container.scope.launch {
            combine(container.playbackSettings.settings, container.network.state) { _, _ -> }.collect {
                val was = container.offline.isOffline
                container.updateOffline()
                container.sync.liveUpdates.refresh()
                if (was && !container.offline.isOffline) container.sync.flushSoon()
            }
        }
        // A removed server's song stops, its downloads leave the device, and what it was still
        // owed is dropped.
        container.scope.launch {
            var known: Set<String>? = null
            container.servers.state.collect { state ->
                if (!state.loaded) return@collect
                val ids = state.servers.map { it.id }.toSet()
                known?.minus(ids)?.forEach { gone ->
                    container.stopMusicOf(gone)
                    container.downloads.removeAll(gone)
                    container.sync.outbox.forget(gone)
                }
                known = ids
            }
        }
        // Switching servers brings the new one's catalogue up to date.
        container.scope.launch {
            var last: String? = null
            container.servers.state.collect { state ->
                if (state.loaded && state.activeId != last) {
                    last = state.activeId
                    container.library.syncActive()
                }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
}
