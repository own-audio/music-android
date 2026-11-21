package audio.own.musicplayer.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.servers.ConnectionMode
import audio.own.musicplayer.app.AppLog
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerDetailState(
    /** The server's version from its discovery, once it answered. */
    val version: String? = null,
    val deleting: Boolean = false,
    val error: UiText? = null,
    val addressError: UiText? = null,
    /** Removed or deleted: the page closes. */
    val gone: Boolean = false,
)

/** One server's page: its connection, account, name, addresses, and removing it. */
class ServerDetailViewModel(
    val serverId: String,
    private val servers: ServersModel,
    private val detector: ServerDetector,
    private val log: AppLog,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerDetailState())
    val state: StateFlow<ServerDetailState> = _state.asStateFlow()
    val serversState: StateFlow<ServersState> = servers.state

    init {
        viewModelScope.launch {
            val record = servers.state.value.server(serverId) ?: return@launch
            if (record.kind == ServerKinds.OWN_AUDIO) {
                val found = detector.discovery((servers.state.value.connections[serverId] as? ConnectionState.Connected)?.url ?: record.host)
                _state.update { it.copy(version = found?.version?.takeIf(String::isNotBlank)) }
            }
        }
    }

    fun use() = viewModelScope.launch { servers.select(serverId) }

    fun checkNow() = viewModelScope.launch { servers.checkNow(serverId) }

    fun rename(name: String) = viewModelScope.launch { servers.rename(serverId, name) }

    fun setConnection(mode: ConnectionMode) = viewModelScope.launch { servers.setConnection(serverId, mode) }

    fun addAddress(text: String) = viewModelScope.launch {
        try {
            servers.addAddress(serverId, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(addressError = e.userMessage()) }
        }
    }

    fun removeAddress(url: String) = viewModelScope.launch { servers.removeAddress(serverId, url) }

    fun dismissErrors() = _state.update { it.copy(error = null, addressError = null) }

    fun remove() {
        _state.update { it.copy(gone = true) }
        // Outlives the page, which closes at once.
        servers.scopeLaunch { servers.remove(serverId) }
    }

    fun deleteAccount(password: String?) {
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            try {
                servers.deleteAccount(serverId, password)
                _state.update { it.copy(gone = true) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.error("servers", "deleting the account failed: ${e.message}")
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(deleting = false) }
            }
        }
    }
}
