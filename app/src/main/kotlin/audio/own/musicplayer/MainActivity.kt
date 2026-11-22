package audio.own.musicplayer

import androidx.core.content.edit
import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.provider.MediaStore
import audio.own.musicplayer.platform.MusicLinks
import audio.own.musicplayer.platform.VoiceSearch
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import audio.own.musicplayer.ui.AppRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The one activity. AppCompat, so the in-app language applies below Android 13 too. */
@UnstableApi
class MainActivity : AppCompatActivity() {
    private val openPlayer = MutableStateFlow(false)
    private lateinit var notifications: ActivityResultLauncher<String>
    private val container get() = (application as OwnAudioApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Phones stay upright, as on iOS; tablets and unfolded foldables turn with the device.
        if (resources.configuration.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        // After a rotation the same intent comes back; it was handled the first time.
        if (savedInstanceState == null) readIntent(intent)
        // The media notification needs the permission from Android 13: asked on the first Play, never at launch.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.playbackModel.plays.collect { askForNotificationsOnce() }
            }
        }
        setContent { AppRoot(container, openPlayer) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.engine.connect(this)
        container.library.syncActive()
        container.sync.flushSoon()
        container.sync.liveUpdates.setForeground(true)
    }

    override fun onStop() {
        // Live updates only while a screen is open; nothing pushes to a phone in the background.
        container.sync.liveUpdates.setForeground(false)
        container.sync.flushSoon()
        container.playback.music.onBackground()
        container.engine.disconnect()
        super.onStop()
    }

    /**
     * A keyboard on a tablet or a Chromebook, wherever the app is: Space plays and pauses,
     * Ctrl+→ and Ctrl+← skip. A text field or the open player takes them first; Esc is left to
     * the system, which makes it Back.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val model = container.playbackModel
        if (model.state.value.currentTrack != null) {
            when {
                keyCode == KeyEvent.KEYCODE_SPACE && !event.isCtrlPressed -> { model.togglePlayPause(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.isCtrlPressed -> { model.next(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.isCtrlPressed -> { model.previous(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * The notification opens the player; the widget's `ownmusic://` links, the launcher's
     * Continue Listening and the assistant's "play ‹album›" play what they name, and the player
     * opens when something does. What can't play is said on the screen.
     */
    private fun readIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
        val requests = container.playRequests
        when (intent.action) {
            ACTION_CONTINUE -> lifecycleScope.launch { if (requests.openAndReport(MusicLinks.Link.Resume)) openPlayer.value = true }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                val focus = when (intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS)) {
                    MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> VoiceSearch.Focus.ALBUM
                    MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> VoiceSearch.Focus.PLAYLIST
                    else -> null
                }
                lifecycleScope.launch { if (requests.playFromSearchAndReport(query, focus)) openPlayer.value = true }
            }
            Intent.ACTION_VIEW -> {
                val link = intent.data?.let(MusicLinks::parse) ?: return
                lifecycleScope.launch { if (requests.openAndReport(link)) openPlayer.value = true }
            }
        }
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        prefs.edit { putBoolean(ASKED, true) }
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "openPlayer"
        const val ACTION_CONTINUE = "audio.own.musicplayer.CONTINUE_LISTENING"
        private const val ASKED = "notificationsAsked"
        private const val TABLET_MIN_WIDTH_DP = 600
    }
}
