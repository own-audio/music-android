package audio.own.musicplayer

import androidx.core.content.edit
import android.Manifest
import android.content.Context
import android.content.Intent
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
        notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        readIntent(intent)
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

    /** Space plays and pauses, Ctrl+→ / Ctrl+← skip, on tablets and Chromebooks; a text field takes them first. */
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

    private fun readIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.value = true
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
        private const val ASKED = "notificationsAsked"
    }
}
