@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import audio.own.musicplayer.MainActivity
import audio.own.musicplayer.player.PlaybackService
import audio.own.musicplayer.platform.MusicLinks
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the widgets' buttons and rows do. */
object WidgetActions {
    enum class Command {
        PAUSE, PREVIOUS, NEXT;

        /** What the command does to the session's player, through the controller. */
        fun sendTo(player: Player) = when (this) {
            PAUSE -> player.pause()
            PREVIOUS -> player.seekToPrevious()
            NEXT -> player.seekToNext()
        }
    }

    val COMMAND = ActionParameters.Key<String>("command")

    /**
     * Play, from the widget as from the notification: a media-button intent the media service
     * gets as a foreground start, which Android allows for a tap on a widget. With nothing in
     * the player it resumes the last queue, as a headset's play button does.
     */
    fun play(context: Context): Action = actionStartService(playIntent(context), isForegroundService = true)

    /** One intent for every widget and size; it never changes. */
    fun playIntent(context: Context): Intent = play ?: Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, PlaybackService::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        .also { play = it }

    @Volatile private var play: Intent? = null

    /** Pause, previous and next go to the media session through a controller, as any other remote does. */
    fun command(command: Command): Action = actionRunCallback<WidgetControlCallback>(actionParametersOf(COMMAND to command.name))

    /** The full player. */
    fun openPlayer(context: Context): Action = actionStartActivity(link(context, MusicLinks.player()))

    /** Opens the app and plays [entry]: an album or playlist, or where you left off. */
    fun open(context: Context, entry: WidgetSnapshot.Entry): Action = actionStartActivity(link(context, entry.link))

    fun openApp(context: Context): Action = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

    private fun link(context: Context, uri: android.net.Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Sends a widget's [WidgetActions.Command] to the media session. */
class WidgetControlCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WidgetActions.COMMAND]?.let { name -> WidgetActions.Command.entries.firstOrNull { it.name == name } } ?: return
        withContext(Dispatchers.Main) {
            val controller = connect(context) ?: return@withContext
            try {
                command.sendTo(controller)
                // The command is on its way before the controller lets go of the session.
                delay(SETTLE_MS)
            } finally {
                controller.release()
            }
        }
    }

    private suspend fun connect(context: Context): MediaController? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        suspendCancellableCoroutine { continuation ->
            future.addListener({ continuation.resume(runCatching { future.get() }.getOrNull()) }, Runnable::run)
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
    }
}
