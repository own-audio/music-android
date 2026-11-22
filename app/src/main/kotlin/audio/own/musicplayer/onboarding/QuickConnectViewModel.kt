package audio.own.musicplayer.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import audio.own.musicplayer.servers.ServersModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QuickConnectState(
    /** The code to approve elsewhere; null while it is being asked for. */
    val code: String? = null,
    /** "Quick Connect Didn't Work", with why. */
    val error: UiText? = null,
    val done: Boolean = false,
)

/**
 * Signs in to a Jellyfin server without typing a password: asks for a code, shows it, and asks
 * every [pollMs] whether it was approved in another Jellyfin app. Leaving the screen ends the
 * waiting with the view model.
 */
class QuickConnectViewModel(
    private val servers: ServersModel,
    private val address: String,
    private val name: String,
    private val log: AppLog,
    /** The saved server whose sign-in this replaces, when signing in again. */
    private val replacing: String? = null,
    private val pollMs: Long = 3_000,
) : ViewModel() {
    private val _state = MutableStateFlow(QuickConnectState())
    val state: StateFlow<QuickConnectState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        run()
    }

    /** Cancel: no more asking whether the code was approved. */
    fun cancel() {
        job?.cancel()
    }

    /** Try Again: the request runs out after a few minutes; a new one gives a new code. */
    fun run() {
        job?.cancel()
        _state.value = QuickConnectState()
        job = viewModelScope.launch {
            try {
                val found = servers.quickConnectServer(address)
                var request = servers.startQuickConnect(found.host)
                _state.value = QuickConnectState(code = request.code)
                while (!request.isApproved) {
                    delay(pollMs)
                    request = servers.checkQuickConnect(found.host, request.secret)
                }
                servers.finishQuickConnect(found, request.secret, name, replacing)
                _state.value = _state.value.copy(done = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("servers", "Quick Connect failed: ${e.message}")
                _state.value = QuickConnectState(error = e.userMessage())
            }
        }
    }
}
