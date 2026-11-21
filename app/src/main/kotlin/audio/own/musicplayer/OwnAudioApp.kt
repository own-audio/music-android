package audio.own.musicplayer

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@UnstableApi
class OwnAudioApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch {
            container.servers.load()
            container.library.syncActive()
        }
        // Offline Mode, "Downloaded Only" on mobile data, or no network at all.
        container.scope.launch {
            combine(container.playbackSettings.settings, container.network.state) { _, _ -> }.collect { container.updateOffline() }
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
}
