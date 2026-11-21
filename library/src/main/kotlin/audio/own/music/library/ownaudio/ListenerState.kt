package audio.own.music.library.ownaudio

import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.RatingRow
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What the listener marked on one own.audio server, favourites and ratings, as the change feed
 * last brought it. v2's list answers don't carry them, so answers that bypass the catalogue
 * (search, random songs, playlists) take them from here; without it every heart would show off.
 */
class ListenerState {
    data class Values(
        /** Ids of favourite tracks, albums and contributors (one id space: they are UUIDs). */
        val favourites: Set<String> = emptySet(),
        /** Ratings of tracks and albums, 1 to 5. */
        val ratings: Map<String, Int> = emptyMap(),
        /** The signed-in account, once known: whose songs and playlists are whose. */
        val userId: String? = null,
    ) {
        companion object {
            /** From the feed's `favourite.*` and `rating.*` records, by kind then id. */
            fun fromFeed(records: Map<String, Map<String, JsonObject>>, userId: String? = null): Values {
                val favourites = HashSet<String>()
                val ratings = HashMap<String, Int>()
                for ((kind, byId) in records) {
                    when {
                        kind.startsWith("favourite.") -> favourites += byId.keys
                        kind.startsWith("rating.") -> for ((id, record) in byId) {
                            (record["rating"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { ratings[id] = it }
                        }
                    }
                }
                return Values(favourites, ratings, userId)
            }

            /** From the catalogue's tables, which the feed fills. */
            fun fromRows(favourites: List<FavouriteRow>, ratings: List<RatingRow>, userId: String?) =
                Values(favourites.mapTo(HashSet()) { it.id }, ratings.filter { it.rating > 0 }.associate { it.id to it.rating }, userId)
        }
    }

    private val values = AtomicReference(Values())

    val current: Values get() = values.get()

    /** After a sync; keeps the account id already learned when the new values have none. */
    fun update(new: Values) {
        values.updateAndGet { old -> new.copy(userId = new.userId ?: old.userId) }
    }

    fun setUserId(id: String) {
        values.updateAndGet { it.copy(userId = id) }
    }

    /** A change made here, before the feed brings it back. */
    fun setFavourite(id: String, on: Boolean) {
        values.updateAndGet { it.copy(favourites = if (on) it.favourites + id else it.favourites - id) }
    }

    fun setRating(id: String, rating: Int) {
        values.updateAndGet { it.copy(ratings = if (rating == 0) it.ratings - id else it.ratings + (id to rating)) }
    }
}
