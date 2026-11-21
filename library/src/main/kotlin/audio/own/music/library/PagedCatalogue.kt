package audio.own.music.library

/**
 * A source without a change feed (Subsonic, Jellyfin): its catalogue on the phone is the whole
 * library read again page by page, at most every [catalogueMaxAgeMs]. Reading everything is
 * heavy, so the app runs it on Wi-Fi only; browsing goes to the server and falls back to this
 * catalogue when the server can't be reached.
 */
interface PagedCatalogue : MusicSource {
    val catalogueMaxAgeMs: Long

    /**
     * Reads the library again when the copy on the phone is older than [catalogueMaxAgeMs] (or
     * always with [force]); null when it was fresh enough. The old copy stays until the new one is
     * complete.
     */
    suspend fun syncCatalogue(force: Boolean = false): PagedSyncResult?

    /** When the catalogue was last read completely; null before the first time. */
    suspend fun catalogueSyncedAtMs(): Long?
}

data class PagedSyncResult(
    val artists: Int,
    val albums: Int,
    val songs: Int,
    val playlists: Int,
    /** The server lists no songs without a search term, so they were read album by album. */
    val readAlbumByAlbum: Boolean,
)
