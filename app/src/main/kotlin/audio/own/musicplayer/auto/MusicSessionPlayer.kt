package audio.own.musicplayer.auto

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * What the media session drives. A controller that picks something (Android Auto, the assistant)
 * goes through the session's callback, which has the app queue the songs with everything the
 * lists know of them (covers, loudness); Media3 then hands the same items to the player again.
 * Here that second hand-over is let go: it would rebuild the queue from the bare items and lose
 * what the app put in it.
 */
@UnstableApi
class MusicSessionPlayer(player: Player) : ForwardingPlayer(player) {
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }

    /** The same songs in the same order as the player holds now. */
    private fun isLoaded(items: List<MediaItem>): Boolean {
        val count = wrappedPlayer.mediaItemCount
        if (count == 0 || items.size != count) return false
        return items.indices.all { items[it].mediaId == wrappedPlayer.getMediaItemAt(it).mediaId }
    }

    companion object {
        /** What the player holds, as the session's answer to a controller that picked something. */
        fun loadedItems(player: Player): List<MediaItem> = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    }
}
