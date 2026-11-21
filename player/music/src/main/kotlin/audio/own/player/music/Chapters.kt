package audio.own.player.music

import kotlinx.serialization.Serializable

/** Where a part of a track begins: one song of a concert recorded as a single file. */
@Serializable
data class TrackChapter(val title: String, val startSecs: Double)

/**
 * What next and previous do inside a track with chapters. Pure, like [PlayQueue], so it can be
 * tested without audio. Each answer is a position to seek to, or null when the skip leaves the
 * track and the queue decides.
 */
object ChapterNavigation {
    /** The chapter playing at [time]: the last one that has started. Null before the first. */
    fun index(chapters: List<TrackChapter>, time: Double): Int? =
        // A seek lands a hair before the mark it aimed at; it still counts as that chapter.
        chapters.indexOfLast { it.startSecs <= time + 0.05 }.takeIf { it >= 0 }

    /** The start of the next chapter; null in the last one, where next goes to the next track. */
    fun next(chapters: List<TrackChapter>, time: Double): Double? {
        val following = (index(chapters, time) ?: -1) + 1
        return chapters.getOrNull(following)?.startSecs
    }

    /**
     * Like previous for tracks: a few seconds into a chapter it restarts that chapter, earlier it
     * goes to the one before. In the first chapter it is null, and the track's own rule applies
     * (restart the track, or go to the previous one).
     */
    fun previous(chapters: List<TrackChapter>, time: Double, restartThreshold: Double = RESTART_THRESHOLD_SECS): Double? {
        val current = index(chapters, time) ?: return null
        if (current == 0) return null
        if (time - chapters[current].startSecs > restartThreshold) return chapters[current].startSecs
        return chapters[current - 1].startSecs
    }
}
