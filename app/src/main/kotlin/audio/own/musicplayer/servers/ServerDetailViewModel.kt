package audio.own.musicplayer.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.servers.ConnectionMode
import audio.own.core.servers.ServerLibraryRef
import audio.own.musicplayer.R
import audio.own.musicplayer.app.Load
import audio.own.musicplayer.library.LibraryModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** What the catalogue on the phone holds; null before the first read. */
    val catalogue: LibraryModel.CatalogueInfo? = null,
    /** The Jellyfin libraries for the picker, while it is open. */
    val libraries: Load<List<ServerLibraryRef>>? = null,
    /** The server identifies with MusicBrainz: Identify Library is offered. */
    val identify: Boolean = false,
)

/** One server's page: its connection, account, name, addresses, and removing it. */
class ServerDetailViewModel(
    val serverId: String,
    private val servers: ServersModel,
    private val detector: ServerDetector,
    private val log: AppLog,
    private val library: LibraryModel? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerDetailState())
    val state: StateFlow<ServerDetailState> = _state.asStateFlow()
    val serversState: StateFlow<ServersState> = servers.state

    init {
        servers.aboutToUse(serverId)
        viewModelScope.launch {
            val record = servers.state.value.server(serverId) ?: return@launch
            if (record.kind == ServerKinds.OWN_AUDIO) {
                val found = detector.discovery((servers.state.value.connections[serverId] as? ConnectionState.Connected)?.url ?: record.host)
                _state.update { it.copy(version = found?.version?.takeIf(String::isNotBlank)) }
            }
        }
    }

    init {
        val library = library
        if (library != null) {
            viewModelScope.launch {
                // Read again after each sync of this server ends.
                library.sync.map { (serverId in it.syncing) to it.revision }.distinctUntilChanged().collect { (syncing, _) ->
                    if (!syncing) _state.update { it.copy(catalogue = runCatching { library.catalogueInfo(serverId) }.getOrNull()) }
                }
            }
        }
    }

    init {
        val source = library?.source(serverId) as? audio.own.music.library.IdentifySource
        if (source != null) {
            viewModelScope.launch {
                val offered = runCatching { source.identifyAvailability() }.getOrNull() != null
                _state.update { it.copy(identify = offered) }
            }
        }
    }

    /** Update Now: the whole catalogue read again, on any network. */
    fun updateNow() {
        library?.syncServer(serverId, force = true)
    }

    /** Music Library…: every library of the server, and All Music Libraries. */
    fun openLibraries() {
        _state.update { it.copy(libraries = Load.Loading) }
        viewModelScope.launch {
            val next = try {
                val found = servers.musicLibraries(serverId).map { ServerLibraryRef(it.id, it.name) }
                if (found.isEmpty()) Load.Failed(UiText(R.string.libraries_failed)) else Load.Loaded(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.userMessage())
            }
            _state.update { if (it.libraries != null) it.copy(libraries = next) else it }
        }
    }

    fun closeLibraries() = _state.update { it.copy(libraries = null) }

    /** One library, or all with null: kept in the record; the client is rebuilt and the catalogue read again. */
    fun chooseLibrary(id: String?) {
        val listed = (_state.value.libraries as? Load.Loaded)?.value
        _state.update { it.copy(libraries = null) }
        viewModelScope.launch {
            servers.setLibrary(serverId, id, listed)
            library?.syncServer(serverId, force = true)
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
