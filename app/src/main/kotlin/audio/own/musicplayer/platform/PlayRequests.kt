package audio.own.musicplayer.platform

import audio.own.core.servers.CompositeId
import audio.own.music.library.MusicSource
import audio.own.music.library.model.Song
import audio.own.musicplayer.R
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.userMessage
import audio.own.musicplayer.ui.playableOffline
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ways in from outside the app: the widget's links, the launcher's Continue Listening, the
 * assistant's "play ‹album›" and Android Auto's spoken requests. Ported from the iOS app's
 * `Platform.swift`. Each call answers null when something plays, else the sentence saying why
 * not; nothing here asks a question. Main thread.
 */
class PlayRequests(
    private val serversLoaded: suspend () -> Unit,
    private val activeServerId: () -> String?,
    /** The active server's source as the screens read it (the downloads when offline). */
    private val source: () -> MusicSource?,
    private val player: Player,
    private val isOffline: () -> Boolean,
    private val isDownloaded: (String) -> Boolean,
) {
    interface Player {
        val hasTrack: Boolean

        /** Plays what is loaded, from where it is. */
        fun resume()

        fun play(songs: List<Song>, startId: String?, containerId: String?)
    }

    private val _message = MutableStateFlow<UiText?>(null)

    /** The last request from outside that couldn't play, until the screen has shown it. */
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** As [open], with the sentence kept for the screen. True when the player should open. */
    suspend fun openAndReport(link: MusicLinks.Link): Boolean {
        val problem = open(link)
        _message.value = problem
        return problem == null && player.hasTrack
    }

    suspend fun playFromSearchAndReport(query: String?, focus: VoiceSearch.Focus? = null): Boolean {
        val problem = playFromSearch(query, focus)
        _message.value = problem
        return problem == null
    }

    suspend fun open(link: MusicLinks.Link): UiText? = when (link) {
        MusicLinks.Link.Player -> null
        MusicLinks.Link.Resume -> continueListening()
        is MusicLinks.Link.Play -> playLink(link.kind, link.id)
    }

    /** What is in the player (the queue from before a relaunch included), from where it stopped. */
    fun continueListening(): UiText? {
        if (!player.hasTrack) return UiText(R.string.nothing_to_continue)
        player.resume()
        return null
    }

    /** An album or playlist by its composite id, on the active server only. */
    suspend fun playLink(kind: MusicLinks.Kind, id: String): UiText? {
        serversLoaded()
        val serverId = CompositeId.split(id)?.first
        if (serverId == null || serverId != activeServerId()) return UiText(R.string.link_other_server)
        val source = source() ?: return UiText(R.string.link_other_server)
        return attempt {
            val songs = when (kind) {
                MusicLinks.Kind.ALBUM -> source.album(id).songs
                MusicLinks.Kind.PLAYLIST -> source.playlist(id).songs
            }
            play(songs, null, "${kind.word}:$id")
        }
    }

    /**
     * "Continue listening", "play my playlist ‹name›", "play the album ‹name›", or a name alone:
     * the playlist of that name, else the album that fits best, else a song.
     */
    suspend fun playFromSearch(query: String?, focus: VoiceSearch.Focus? = null): UiText? {
        val request = VoiceSearch.parse(query, focus)
        if (request == VoiceSearch.Request.Continue) return continueListening()
        serversLoaded()
        val source = source() ?: return UiText(R.string.cant_play_now)
        return attempt {
            when (request) {
                is VoiceSearch.Request.Playlist -> {
                    val playlist = VoiceSearch.rankedPlaylists(request.name, source.playlists()).firstOrNull()?.first
                        ?: return@attempt notFound(request.name)
                    play(source.playlist(playlist.id).songs, null, "playlist:${playlist.id}")
                }
                is VoiceSearch.Request.Album -> {
                    val album = VoiceSearch.rankedAlbums(request.name, source.search(request.name).albums).firstOrNull()?.first
                        ?: return@attempt notFound(request.name)
                    play(source.album(album.id).songs, null, "album:${album.id}")
                }
                is VoiceSearch.Request.Anything -> anything(source, request.text)
                VoiceSearch.Request.Continue -> continueListening()
            }
        }
    }

    private suspend fun anything(source: MusicSource, text: String): UiText? {
        val playlist = VoiceSearch.rankedPlaylists(text, runCatching { source.playlists() }.getOrDefault(emptyList())).firstOrNull()
        val found = source.search(text)
        val album = VoiceSearch.rankedAlbums(text, found.albums).firstOrNull()
        if (playlist != null && playlist.second >= (album?.second ?: 0)) {
            return play(source.playlist(playlist.first.id).songs, null, "playlist:${playlist.first.id}")
        }
        if (album != null) return play(source.album(album.first.id).songs, null, "album:${album.first.id}")
        val song = found.songs.map { it to VoiceSearch.score(text, it.title) }.filter { it.second >= 30 }.maxByOrNull { it.second }?.first
            ?: return notFound(text)
        return play(found.songs, song.id, null)
    }

    /** Songs a row in the car chose; offline only the downloaded ones. */
    fun playSongs(songs: List<Song>, startId: String?, containerId: String?): UiText? = play(songs, startId, containerId)

    private fun play(songs: List<Song>, startId: String?, containerId: String?): UiText? {
        if (songs.isEmpty()) return UiText(R.string.cant_play_now)
        val (playable, first) = if (isOffline()) playableOffline(songs, startId, isDownloaded) else songs to startId
        if (playable.isEmpty()) return UiText(R.string.offline_none_downloaded)
        player.play(playable, first, containerId)
        return null
    }

    private fun notFound(text: String) = UiText.of(R.string.voice_not_found, text)

    private inline fun attempt(work: () -> UiText?): UiText? = try {
        work()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.userMessage()
    }
}
