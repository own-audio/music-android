package audio.own.musicplayer.library

import audio.own.core.servers.CompositeId
import audio.own.kit.ownaudio.IdentifyQuote
import audio.own.kit.ownaudio.IdentifyScope
import audio.own.music.library.ArtworkSource
import audio.own.music.library.HouseholdSource
import audio.own.music.library.IdentifySource
import audio.own.music.library.MusicSource
import audio.own.music.library.identify.IdentifyStart
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Sharing
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.UserFacingException
import audio.own.musicplayer.app.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whose pictures a chosen photo becomes (music-ios `AppModel.ArtworkTarget`). */
sealed interface ArtworkTarget {
    /** The listener's own songs among these: a song's Choose Cover…. */
    data class Songs(val songs: List<Song>) : ArtworkTarget

    /** Choose Album Cover…: every own song of the album, the album's songs known so far. */
    data class Album(val album: audio.own.music.library.model.Album, val songs: List<Song>) : ArtworkTarget

    data class ArtistPicture(val artist: Artist) : ArtworkTarget
}

/** What the app-wide sheets and dialogs of own.audio's extras show, one at a time. */
sealed interface OwnAudioRequest {
    /** Identify…: the recording picker for one song. */
    data class IdentifySong(val song: Song) : OwnAudioRequest

    /** Undo Identify…: asks first. */
    data class UndoIdentify(val song: Song) : OwnAudioRequest

    /** A run that costs something: the price, then Start. */
    data class Confirm(val serverId: String, val quote: IdentifyQuote, val scope: IdentifyScope) : OwnAudioRequest

    /** A run just started: shown at once. */
    data class OpenRun(val serverId: String, val runId: String) : OwnAudioRequest

    /** Starting a run failed: "Couldn't Start Identifying" with why. */
    data class StartFailed(val message: UiText) : OwnAudioRequest
}

/**
 * The menus' actions on the listener's own songs where the server keeps owners (own.audio):
 * sharing with the household, their pictures, and identify. Shown at once and put back when
 * the server refuses, as music-ios `AppModel.setShared` and `setArtwork`; the catalogue on the
 * phone catches up after each change that it holds.
 */
class OwnAudioActions(
    /** The server's own source by composite id, never the offline wrapper. */
    private val sourceOf: (String) -> MusicSource?,
    private val sourceForServer: (String) -> MusicSource?,
    private val isOffline: () -> Boolean,
    /** Brings a server's catalogue up to date now (after identify changed titles). */
    private val syncServer: (String) -> Unit,
    /** Cached covers of these ids are dropped and drawn again. */
    private val forgetCovers: (Collection<String>) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _sharing = MutableStateFlow<Map<String, Sharing>>(emptyMap())

    /** Sharing changed here, by song id, until the catalogue brings it back. */
    val sharingChanges: StateFlow<Map<String, Sharing>> = _sharing.asStateFlow()

    private val _request = MutableStateFlow<OwnAudioRequest?>(null)
    val request: StateFlow<OwnAudioRequest?> = _request.asStateFlow()

    fun sharing(song: Song): Sharing? = _sharing.value[song.id] ?: song.sharing

    /** Sharing is offered on [serverId]'s songs: a household, and a network. */
    fun canShare(serverId: String): Boolean = !isOffline() && sourceForServer(serverId) is HouseholdSource

    fun canChoosePictures(serverId: String): Boolean = !isOffline() && sourceForServer(serverId) is ArtworkSource

    /** The listener's own among [songs], shared or kept to themselves; put back if refused. */
    suspend fun setShared(songs: List<Song>, shared: Boolean) {
        val mine = songs.filter { sharing(it)?.isMine == true }
        val first = mine.firstOrNull() ?: return
        val household = sourceOf(first.id) as? HouseholdSource ?: return
        val before = mine.associate { it.id to _sharing.value[it.id] }
        val now = if (shared) Sharing.SHARED else Sharing.MINE
        _sharing.update { it + mine.associate { s -> s.id to now } }
        try {
            household.setShared(mine.map { it.id }, shared)
        } catch (e: Exception) {
            restore(before)
            throw e
        }
    }

    /**
     * An album's Share My Songs: every song of the account on it, the ones not loaded yet too.
     * [songs] are those on screen, marked at once.
     */
    suspend fun setAlbumShared(albumId: String, songs: List<Song>, shared: Boolean): Int {
        val household = sourceOf(albumId) as? HouseholdSource ?: return 0
        val mine = songs.filter { sharing(it)?.isMine == true }
        val before = mine.associate { it.id to _sharing.value[it.id] }
        val now = if (shared) Sharing.SHARED else Sharing.MINE
        _sharing.update { it + mine.associate { s -> s.id to now } }
        return try {
            household.setAlbumShared(albumId, shared)
        } catch (e: Exception) {
            restore(before)
            throw e
        }
    }

    private fun restore(before: Map<String, Sharing?>) = _sharing.update { map ->
        val next = map.toMutableMap()
        for ((id, old) in before) if (old == null) next.remove(id) else next[id] = old
        next
    }

    /** [jpeg] is the picture as the app made it; the covers drawn for the changed ids reload. */
    suspend fun setArtwork(target: ArtworkTarget, jpeg: ByteArray) {
        val changed = when (target) {
            is ArtworkTarget.Songs -> {
                val mine = target.songs.filter { sharing(it)?.isMine == true }
                val first = mine.firstOrNull() ?: return
                val artwork = sourceOf(first.id) as? ArtworkSource ?: return
                artwork.setCover(mine.map { it.id }, null, jpeg, CONTENT_TYPE)
                mine.flatMap { listOfNotNull(it.id, it.coverArt, it.albumId) }
            }
            is ArtworkTarget.Album -> {
                val album = target.album
                val artwork = sourceOf(album.id) as? ArtworkSource ?: return
                val own = artwork.ownSongIds(album.id)
                if (own.isEmpty()) return
                artwork.setCover(own, album.id, jpeg, CONTENT_TYPE)
                own + listOfNotNull(album.id, album.coverArt) + target.songs.mapNotNull { it.coverArt }
            }
            is ArtworkTarget.ArtistPicture -> {
                val artwork = sourceOf(target.artist.id) as? ArtworkSource ?: return
                artwork.setPicture(target.artist, jpeg, CONTENT_TYPE)
                listOfNotNull(target.artist.id, target.artist.coverArt)
            }
        }
        forgetCovers(changed.toSet())
    }

    // Identify

    fun identifySource(serverId: String): IdentifySource? = if (isOffline()) null else sourceForServer(serverId) as? IdentifySource

    /** The server identifies only its owner's songs; on someone else's it could only fail. */
    fun mayIdentify(song: Song): Boolean = sharing(song)?.isMine != false

    fun identify(song: Song) = _request.update { OwnAudioRequest.IdentifySong(song) }

    fun askUndoIdentify(song: Song) = _request.update { OwnAudioRequest.UndoIdentify(song) }

    fun dismiss() = _request.update { null }

    fun openRun(serverId: String, runId: String) = _request.update { OwnAudioRequest.OpenRun(serverId, runId) }

    /** Puts the song back as its file says; one that never was identified says so. */
    suspend fun undoIdentify(song: Song) {
        val source = sourceOf(song.id) as? IdentifySource ?: return
        if (!source.undoIdentify(song.id)) throw UserFacingException(R.string.not_identified)
        changed(song.id)
    }

    /** A song identified (or put back) here: the catalogue on the phone catches up. */
    fun changed(id: String) {
        CompositeId.split(id)?.first?.let(syncServer)
    }

    fun changedServer(serverId: String) = syncServer(serverId)

    /** Identify Album / Identify Artist / a folder: the price first where it costs, then the run. */
    fun beginRun(serverId: String, scope: IdentifyScope) {
        val source = identifySource(serverId) ?: return
        this.scope.launch {
            try {
                val availability = source.identifyAvailability() ?: return@launch
                when (val start = source.identifyRuns(availability).begin(scope)) {
                    is IdentifyStart.Confirm -> _request.value = OwnAudioRequest.Confirm(serverId, start.quote, start.scope)
                    is IdentifyStart.Started -> _request.value = OwnAudioRequest.OpenRun(serverId, start.run.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _request.value = OwnAudioRequest.StartFailed(e.userMessage())
            }
        }
    }

    /** Start, after the price was shown. */
    fun startConfirmed(request: OwnAudioRequest.Confirm) {
        val source = identifySource(request.serverId) ?: return dismiss()
        _request.value = null
        scope.launch {
            try {
                val availability = source.identifyAvailability() ?: return@launch
                val run = source.identifyRuns(availability).start(request.scope)
                _request.value = OwnAudioRequest.OpenRun(request.serverId, run.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _request.value = OwnAudioRequest.StartFailed(e.userMessage())
            }
        }
    }

    private companion object {
        const val CONTENT_TYPE = "image/jpeg"
    }
}
