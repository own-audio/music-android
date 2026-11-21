package audio.own.player.music

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * What happens when the current song finishes on its own. Manual next and previous always move
 * one position whatever this says: hijacking a deliberate skip would trap a listener in the
 * current song while [ONE] is on.
 */
@Serializable
enum class RepeatMode {
    OFF, ALL, ONE;

    /** The button cycles Off → All → One → Off. */
    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Previous more than this many seconds into a song (or a concert's chapter) restarts it. */
const val RESTART_THRESHOLD_SECS = 3.0

/**
 * The order songs play in, free of any player. [tracks] is the list as a screen shows it (the
 * album or playlist order, plus what was added); `order` is a separate permutation of indices
 * into it, so turning shuffle off restores the list's order exactly rather than re-deriving it.
 *
 * Not thread-safe: the player owns one and changes it on its own thread.
 */
class PlayQueue(
    tracks: List<Track>,
    startTrackId: String? = null,
    private val random: Random = Random.Default,
) {
    var tracks: List<Track> = tracks.toList()
        private set
    private var order: MutableList<Int> = this.tracks.indices.toMutableList()
    var position: Int = 0
        private set
    var isShuffled: Boolean = false
        private set

    init {
        val trackIndex = startTrackId?.let { id -> this.tracks.indexOfFirst { it.id == id } } ?: -1
        position = if (trackIndex >= 0) order.indexOf(trackIndex).coerceAtLeast(0) else 0
    }

    val currentTrack: Track? get() = order.getOrNull(position)?.let { tracks[it] }
    val nextTrack: Track? get() = order.getOrNull(position + 1)?.let { tracks[it] }
    val previousTrack: Track? get() = order.getOrNull(position - 1)?.let { tracks[it] }

    val isAtLastTrack: Boolean get() = position >= order.size - 1

    data class Position(val current: Int, val total: Int)

    /** Where the current song sits in play order, counting from one: "Track 1 of 5". */
    val positionDisplay: Position get() = Position(position + 1, order.size)

    /**
     * Where the current song sits **in the list as a screen shows it**: a different number from
     * [positionDisplay] the moment shuffle is on, and the only one a listener can check against
     * the rows in front of them.
     */
    val listPositionDisplay: Position?
        get() {
            val current = currentTrack ?: return null
            val index = tracks.indexOfFirst { it.id == current.id }
            return if (index < 0) null else Position(index + 1, tracks.size)
        }

    /**
     * The songs up to but not including the current one, in play order: what has been heard on
     * the way here, which a container-wide progress bar measures against.
     */
    val played: List<Track> get() = if (position <= 0) emptyList() else order.subList(0, minOf(position, order.size)).map { tracks[it] }

    /** Every song after the current one, in play order: the queue sheet's own list. */
    val upcoming: List<Track> get() = if (position + 1 >= order.size) emptyList() else order.subList(position + 1, order.size).map { tracks[it] }

    /**
     * Moves to the next song, or null once the queue is exhausted; the player decides what
     * "queue finished" means.
     */
    fun advance(): Track? {
        if (isAtLastTrack) {
            position = order.size
            return null
        }
        position += 1
        return currentTrack
    }

    fun goToPrevious(): Track? {
        if (position > 0) position -= 1
        return currentTrack
    }

    /** Jumps to a song by id; an unknown id leaves the queue where it was and answers null. */
    fun jumpTo(trackId: String): Track? {
        val trackIndex = tracks.indexOfFirst { it.id == trackId }
        if (trackIndex < 0) return null
        val orderPosition = order.indexOf(trackIndex)
        if (orderPosition < 0) return null
        position = orderPosition
        return currentTrack
    }

    /**
     * Lookahead without moving: what plays after the current song under [repeatMode], for
     * everything that needs to know before the song ends (gapless prefetch, crossfade).
     */
    fun trackAfterCurrent(repeatMode: RepeatMode): Track? = when (repeatMode) {
        RepeatMode.ONE -> currentTrack
        RepeatMode.OFF -> nextTrack
        RepeatMode.ALL -> nextTrack ?: order.firstOrNull()?.let { tracks[it] }
    }

    /**
     * The repeat-aware [advance], for a song that finished on its own: [RepeatMode.ONE] stays
     * put (the next song is this one again) and [RepeatMode.ALL] wraps to the start instead of
     * running off the end.
     */
    fun advance(repeatMode: RepeatMode): Track? = when (repeatMode) {
        RepeatMode.ONE -> currentTrack
        RepeatMode.OFF -> advance()
        RepeatMode.ALL -> if (!isAtLastTrack) advance() else {
            position = 0
            currentTrack
        }
    }

    /**
     * Turns shuffle on or off without disturbing what has already been played.
     *
     * Shuffling the whole list and then hunting for the playing song would, on song 1 of 17,
     * routinely leave a dozen unplayed songs *behind* the cursor: they would never play, the
     * album would claim to be two thirds done, and its remaining time would fall by an hour at
     * the press of a button. So only what is still to come is shuffled; the songs already heard
     * stay behind in the order they were heard, and the current one stays current.
     */
    fun setShuffled(shuffled: Boolean) {
        isShuffled = shuffled
        if (!shuffled) {
            val currentId = currentTrack?.id
            order = tracks.indices.toMutableList()
            if (currentId != null) {
                val trackIndex = tracks.indexOfFirst { it.id == currentId }
                if (trackIndex >= 0) position = order.indexOf(trackIndex)
            }
            return
        }
        val heard = order.subList(0, minOf(position, order.size)).toList()
        val current = listOfNotNull(order.getOrNull(position))
        val remaining = if (position + 1 < order.size) order.subList(position + 1, order.size).shuffled(random) else emptyList()
        // position is unchanged on purpose: heard.size == position still holds.
        order = (heard + current + remaining).toMutableList()
    }

    /**
     * Shuffles and starts at the top of the new order: "Shuffle" on an album or artist, where
     * there is no current song to protect. [setShuffled] would pin song 1 of a queue just built,
     * so every shuffle would begin with the same song.
     */
    fun shuffleFromStart() {
        order = tracks.indices.shuffled(random).toMutableList()
        isShuffled = true
        position = 0
    }

    // Editing what is still to come

    /**
     * Play Next: puts [incoming] right after the current song, in their order. A song already in
     * the queue moves rather than appearing twice (ids find songs here); the current song stays.
     */
    fun insertNext(incoming: List<Track>) = insert(incoming, { position + 1 }, canonicalAfterCurrent = true)

    /** Add to Queue: puts [incoming] at the end, in their order; a song already waiting moves there. */
    fun append(incoming: List<Track>) = insert(incoming, { order.size }, canonicalAfterCurrent = false)

    /**
     * Moves rows of [upcoming], as a list's drag does: [offsets] and [destination] count from the
     * first upcoming song, and [destination] is the gap the rows are dropped into (before the
     * row that is there now).
     */
    fun moveUpcoming(offsets: Collection<Int>, destination: Int) {
        val base = position + 1
        if (base >= order.size) return
        val upcoming = order.subList(base, order.size).toMutableList()
        val valid = offsets.toSortedSet().filter { it in upcoming.indices }
        val moving = valid.map { upcoming[it] }
        val before = valid.count { it < destination }
        for (offset in valid.sortedDescending()) upcoming.removeAt(offset)
        upcoming.addAll((destination - before).coerceIn(0, upcoming.size), moving)
        order = (order.subList(0, base) + upcoming).toMutableList()
        settleCanonicalOrder()
    }

    /** Removes rows of [upcoming], counted from the first upcoming song. */
    fun removeUpcoming(offsets: Collection<Int>) {
        val upcoming = upcoming
        val ids = offsets.mapNotNull { upcoming.getOrNull(it)?.id }
        for (id in ids) {
            val index = tracks.indexOfFirst { it.id == id }
            if (index >= 0) removeTrack(index)
        }
    }

    private fun insert(incoming: List<Track>, at: () -> Int, canonicalAfterCurrent: Boolean) {
        val currentId = currentTrack?.id
        val seen = HashSet<String>()
        val adding = incoming.filter { it.id != currentId && seen.add(it.id) }
        if (adding.isEmpty()) return
        for (track in adding) {
            val index = tracks.indexOfFirst { it.id == track.id }
            if (index >= 0) removeTrack(index)
        }
        var orderPosition = minOf(at(), order.size)
        // Without shuffle the list a screen shows is the play order, so new songs go into it
        // where they will play.
        var canonical = when {
            !isShuffled -> orderPosition
            canonicalAfterCurrent -> currentTrack?.let { current -> tracks.indexOfFirst { it.id == current.id } + 1 } ?: tracks.size
            else -> tracks.size
        }
        val list = tracks.toMutableList()
        for (track in adding) {
            list.add(canonical, track)
            order = order.map { if (it >= canonical) it + 1 else it }.toMutableList()
            order.add(orderPosition, canonical)
            canonical += 1
            orderPosition += 1
        }
        tracks = list
    }

    /** Drops one song from both orders, keeping the current song current. */
    private fun removeTrack(index: Int) {
        val orderIndex = order.indexOf(index)
        if (orderIndex < 0 || (orderIndex == position && position < order.size)) return
        tracks = tracks.toMutableList().also { it.removeAt(index) }
        order.removeAt(orderIndex)
        order = order.map { if (it > index) it - 1 else it }.toMutableList()
        if (orderIndex < position) position -= 1
    }

    /** Without shuffle, the list's order is the play order: a move changes both. */
    private fun settleCanonicalOrder() {
        if (isShuffled) return
        tracks = order.map { tracks[it] }
        order = tracks.indices.toMutableList()
    }
}

/**
 * Ids of what a queue was started from, for the resume store and the last queue. Scoped by
 * server, since two servers can have an album with the same id.
 */
object PlayContainer {
    const val FAVORITES = "favorites"

    fun album(id: String) = "album:$id"
    fun playlist(id: String) = "playlist:$id"
    fun mix(id: String) = "mix:$id"

    fun scoped(serverId: String, containerId: String) = "$serverId|$containerId"
}
