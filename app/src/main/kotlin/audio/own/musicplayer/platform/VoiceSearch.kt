package audio.own.musicplayer.platform

import audio.own.music.library.model.Album
import audio.own.music.library.model.Playlist
import java.text.Normalizer
import java.util.Locale

/**
 * What the assistant, the car's search and a "play from search" intent asked for. Ported from the
 * entity queries of the iOS app's `MusicIntents`: "continue listening", "play my playlist
 * ‹name›", "play the album ‹name›", or just a name, which may be either. Words are compared
 * without accents or case, in English and Czech, so "Pust muj playlist Vylet" finds "Výlet".
 */
object VoiceSearch {
    sealed interface Request {
        data object Continue : Request

        data class Playlist(val name: String) : Request

        data class Album(val name: String) : Request

        /** A name with nothing said about what it names. */
        data class Anything(val text: String) : Request
    }

    /** What the system says the request is about (`MediaStore.EXTRA_MEDIA_FOCUS`), when it says. */
    enum class Focus { ALBUM, PLAYLIST }

    /** Lower case, accents and punctuation gone, single spaces. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return decomposed.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim().replace(SPACES, " ")
    }

    /**
     * The request, with the name as it was said (case and accents kept, for the server's
     * search): the words are dropped from the folded text and the same words from the original.
     */
    fun parse(query: String?, focus: Focus? = null): Request {
        if (query.isNullOrBlank()) return Request.Continue
        val folded = fold(query).split(' ').filter { it.isNotEmpty() }
        val said = Normalizer.normalize(query, Normalizer.Form.NFC).split(NOT_WORD_OR_MARK).filter { it.isNotEmpty() }
        val original = if (said.size == folded.size) said else folded
        var from = 0
        var to = folded.size
        fun rest() = folded.subList(from, to).joinToString(" ")
        fun name() = original.subList(from, to).joinToString(" ")
        fun dropPrefix(phrases: List<String>): Boolean {
            val phrase = phrases.firstOrNull { rest().startsWith("$it ") } ?: return false
            from += phrase.split(' ').size
            return true
        }
        if (rest() in CONTINUE_PHRASES) return Request.Continue
        APP_SUFFIXES.firstOrNull { rest().endsWith(" $it") }?.let { to -= it.split(' ').size }
        if (rest() in CONTINUE_PHRASES) return Request.Continue
        dropPrefix(VERBS)
        if (from >= to || rest() in CONTINUE_PHRASES) return Request.Continue
        if (dropPrefix(PLAYLIST_WORDS)) return Request.Playlist(name())
        if (dropPrefix(ALBUM_WORDS)) return Request.Album(name())
        return when (focus) {
            Focus.PLAYLIST -> Request.Playlist(name())
            Focus.ALBUM -> Request.Album(name())
            null -> Request.Anything(name())
        }
    }

    /** How well [candidate] answers [query], 0 (not at all) to 100 (the same words). */
    fun score(query: String, candidate: String): Int {
        val q = fold(query)
        val c = fold(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        if (c.startsWith(q) || q.startsWith(c)) return 80
        if (c.contains(q)) return 70
        val words = q.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return 0
        val theirs = c.split(' ').toSet()
        val hits = words.count { word -> theirs.any { it == word || (word.length >= 4 && it.startsWith(word)) } }
        return if (hits == 0) 0 else 60 * hits / words.size
    }

    fun rankedPlaylists(name: String, playlists: List<Playlist>): List<Pair<Playlist, Int>> =
        playlists.map { it to score(name, it.name) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }

    /** "Kind of Blue" and "Kind of Blue Miles Davis" both find the album; the artist alone counts less. */
    fun rankedAlbums(name: String, albums: List<Album>): List<Pair<Album, Int>> =
        albums.map { album ->
            val withArtist = album.artist?.let { score(name, "${album.name} ${it}") - 5 } ?: 0
            val byArtist = album.artist?.let { score(name, it) - 30 } ?: 0
            album to maxOf(score(name, album.name), withArtist, byArtist)
        }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }

    private const val MIN_SCORE = 30
    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val NOT_WORD_OR_MARK = Regex("[^\\p{L}\\p{N}\\p{Mn}]+")
    private val SPACES = Regex(" +")
    private val VERBS = listOf("play", "listen to", "put on", "start", "prehraj", "prehrat", "zahraj", "pust", "pustit", "poslouchat", "spust")
    private val APP_SUFFIXES = listOf(
        "on own music", "in own music", "with own music", "on own audio music", "in own audio music", "own music",
        "v own music", "na own music", "v aplikaci own music",
    ).sortedByDescending { it.length }
    private val PLAYLIST_WORDS = listOf("my playlist", "the playlist", "muj playlist", "playlist")
    private val ALBUM_WORDS = listOf("the album", "my album", "muj album", "desku", "album")
    private val CONTINUE_PHRASES = setOf(
        "continue", "continue listening", "resume", "resume listening", "keep listening", "resume my music", "continue my music",
        "play", "prehraj", "my music", "music", "some music",
        "pokracovat", "pokracuj", "pokracuj v poslechu", "pokracovat v poslechu", "pust dal mou hudbu", "pust dal moji hudbu", "dal mou hudbu", "dal moji hudbu", "dal",
        "moje hudba", "mou hudbu", "hudbu", "hudba",
    )
}
