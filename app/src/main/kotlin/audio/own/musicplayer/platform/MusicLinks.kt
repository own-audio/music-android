package audio.own.musicplayer.platform

import android.net.Uri
import androidx.core.net.toUri

/**
 * The app's own links, from the widget and the launcher: `ownmusic://resume`,
 * `ownmusic://play/album/<id>`, `ownmusic://play/playlist/<id>` and `ownmusic://player`. Ids are
 * composite (`<server>|<item>`). Ported from the iOS app's `WidgetSnapshot.link(from:)`.
 */
object MusicLinks {
    const val SCHEME = "ownmusic"

    enum class Kind(val word: String) { ALBUM("album"), PLAYLIST("playlist") }

    sealed interface Link {
        /** Go on with what is in the player. */
        data object Resume : Link

        /** Open the full player. */
        data object Player : Link

        data class Play(val kind: Kind, val id: String) : Link
    }

    fun resume(): Uri = "$SCHEME://resume".toUri()

    fun player(): Uri = "$SCHEME://player".toUri()

    /** The id carries a `|` and whatever the server put in it, so it is escaped whole. */
    fun play(kind: Kind, id: String): Uri = "$SCHEME://play/${kind.word}/${Uri.encode(id)}".toUri()

    /** What a link asks for, or null for any other link. */
    fun parse(uri: Uri): Link? {
        if (uri.scheme != SCHEME) return null
        return when (uri.host) {
            "resume" -> Link.Resume
            "player" -> Link.Player
            "play" -> {
                val parts = uri.toString().substringAfter("$SCHEME://play/", "").split('/', limit = 2)
                if (parts.size != 2) return null
                val kind = Kind.entries.firstOrNull { it.word == parts[0] } ?: return null
                val id = Uri.decode(parts[1]).takeIf { it.isNotBlank() } ?: return null
                Link.Play(kind, id)
            }
            else -> null
        }
    }
}
