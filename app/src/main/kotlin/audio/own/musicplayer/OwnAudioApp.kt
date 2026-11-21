package audio.own.musicplayer

import android.app.Application
import audio.own.core.servers.MobileData
import kotlinx.coroutines.launch

class OwnAudioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch { container.servers.load() }
        // Offline Mode and the mobile-data choice come with the settings (M9); for now only the network decides.
        container.scope.launch { container.network.state.collect { container.offline.update(false, it, MobileData.ORIGINAL) } }
    }
}
