package audio.own.music.library

import audio.own.kit.ownaudio.IdentifyScope
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.RecordingCandidate
import audio.own.music.library.identify.IdentifyRunReview
import audio.own.music.library.identify.IdentifyRuns
import audio.own.music.library.identify.RecordingResults
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.music.library.model.Song
import audio.own.music.library.model.SongChapter
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// Features only own.audio servers have. Each is its own interface, so a screen asks
// `source is HouseholdSource` (and that the server is signed in) instead of a capability flag,
// and the other sources don't carry methods they can only refuse.

/** How far a catalogue sync got. */
data class CatalogueSyncResult(
    /** A whole new snapshot was read (first sync, or the server said the changes were too old). */
    val fromSnapshot: Boolean,
    /** Records written or removed. */
    val changes: Int,
    val cursor: String,
)

/**
 * A source that keeps the catalogue on the phone up to date from a change feed: the whole
 * library once, then only what changed. Sources without one are synced by paging.
 */
interface ChangeFeedCatalogue : MusicSource {
    /** Brings the catalogue up to date, from scratch when there is none or the server says so. */
    suspend fun syncCatalogue(): CatalogueSyncResult
}

/** A change made while offline, kept on the phone and sent later. */
data class OfflineEdit(
    /** The phone's own id for it, which makes sending it twice harmless. */
    val clientId: String,
    val itemId: String,
    val change: Change,
    val madeAt: Instant,
) {
    sealed interface Change {
        data class Favorite(val kind: FavoriteKind, val on: Boolean) : Change

        /** A song or an album; 0 takes the rating away. */
        data class Rating(val kind: FavoriteKind, val rating: Int) : Change

        /** A song heard for [heardMs] from [startedAt]. */
        data class Played(val startedAt: Instant, val heardMs: Long) : Change
    }
}

/** A source that takes what was changed offline in one request rather than one call each. */
interface BatchedOfflineEdits : MusicSource {
    /**
     * Sends [edits] in the order made. Returns once the server has answered every one, including
     * those it refused for good (the song is gone), which are not worth sending again. Throws when
     * nothing was answered; [SourceError.Unsupported] means they go one call at a time.
     */
    suspend fun push(edits: List<OfflineEdit>)
}

/**
 * A source that says when the library changed elsewhere, so the catalogue catches up at once
 * rather than at the next scheduled sync.
 */
interface LiveLibraryUpdates : MusicSource {
    /** One element per change, until the connection ends (normally or with an error). */
    fun libraryChanges(): Flow<Unit>
}

// region Household

data class HouseholdInfo(
    val name: String,
    /** For [HouseholdSource.picture]. */
    val picture: String?,
    /** Admins invite people. */
    val isAdmin: Boolean,
    val members: List<HouseholdMember>,
)

data class HouseholdMember(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    /** Invited, not signed in yet. */
    val isPending: Boolean,
    val isMe: Boolean,
    val picture: String?,
)

/** A code that lets someone join; [joinUrl] opens the server's own join page with it. */
data class HouseholdInvite(
    val id: String,
    val code: String,
    val joinUrl: String?,
    val usesLeft: Int,
    /** Null when the server's date can't be read. */
    val expiresAt: Instant?,
)

/** What the family keeps on the server, in bytes. */
data class HouseholdStorage(val total: Long, val music: Long, val audiobooks: Long, val podcasts: Long)

/** The family's prepaid credit, where the server charges for storage (the hosted service). */
data class HouseholdCredit(
    val balance: BigDecimal,
    val currency: String,
    val monthlyCost: BigDecimal,
    /** How long the balance lasts at today's storage; null when it isn't being drawn on. */
    val daysLeft: Int?,
    val isDepleted: Boolean,
)

/** A server where each song and playlist has an owner, who alone shares or changes it. */
interface OwnedItems : MusicSource {
    /** The listener's own songs of an album, in album order; others' songs on it are left out. */
    suspend fun ownSongIds(albumId: String): List<String>
}

interface HouseholdSource : OwnedItems {
    suspend fun household(): HouseholdInfo

    /** The family's or a member's picture, by what [HouseholdInfo] names it. */
    suspend fun picture(name: String): ByteArray
    suspend fun storage(): HouseholdStorage

    /** Null where the server doesn't charge. */
    suspend fun credit(): HouseholdCredit?
    suspend fun invites(): List<HouseholdInvite>
    suspend fun createInvite(): HouseholdInvite
    suspend fun deleteInvite(id: String)

    /** Moves the account into the household the code belongs to. */
    suspend fun joinHousehold(code: String): HouseholdInfo

    /** The listener's own songs: shared with the household, or kept to themselves. */
    suspend fun setShared(songIds: List<String>, shared: Boolean)

    /** An album's "Share My Songs": each of the listener's own songs on it; how many. */
    suspend fun setAlbumShared(albumId: String, shared: Boolean): Int
    suspend fun setShared(playlistId: String, shared: Boolean)
}

// endregion

/**
 * A server that takes the listener's own pictures: songs' covers (an album's cover is its
 * songs'), artists' pictures, playlists' covers.
 */
interface ArtworkSource : OwnedItems {
    /**
     * Each of the listener's own songs given the picture; [albumId] when they are an album's.
     * [image] is what the app made of the chosen picture (resized, JPEG or PNG), sent as it is.
     */
    suspend fun setCover(songIds: List<String>, albumId: String?, image: ByteArray, contentType: String)
    suspend fun setPicture(artist: Artist, image: ByteArray, contentType: String)
    suspend fun setCover(playlistId: String, image: ByteArray, contentType: String)
}

/** Playlist edits beyond what every source has. */
interface PlaylistDetailsSource : MusicSource {
    /** Null or blank takes it away. */
    suspend fun setPlaylistDescription(id: String, description: String?)

    /**
     * A new order: every entry id of the playlist ([audio.own.music.library.model.Song.playlistEntryId]),
     * in the order wanted. A song can be in a playlist twice, so it goes by entries, not songs.
     */
    suspend fun reorderPlaylist(id: String, entryIds: List<String>)
}

// region Smart playlists

/** A smart playlist's rule: what own.audio's API takes, field for field. */
typealias SmartRule = audio.own.kit.ownaudio.SmartRule

/**
 * A saved rule. What it holds is worked out each time it is played, from the listener's own
 * history: shared with the household, it is a different list for each member.
 */
data class SmartPlaylistInfo(
    val id: String,
    val name: String,
    val rule: SmartRule,
    val isShared: Boolean,
    /** The listener's own: only they change or delete it. */
    val isMine: Boolean,
    /** Made into an ordinary playlist; the rule is kept. */
    val isFrozen: Boolean,
)

/** A ready-made rule from the server, the same for everyone. */
data class SmartPresetInfo(val slug: String, val name: String, val rule: SmartRule)

/** What a rule holds now, in the order to play it. */
data class SmartResult(
    val songs: List<Song>,
    /** How many songs matched before the rule's length and artist spacing were applied. */
    val candidates: Int,
    val durationMs: Long,
)

/** A server with smart playlists. One without them answers NotFound or Unsupported, and the app shows none. */
interface SmartPlaylistSource : MusicSource {
    suspend fun smartPlaylists(): List<SmartPlaylistInfo>
    suspend fun smartPresets(): List<SmartPresetInfo>

    /** Tries a rule without saving it. */
    suspend fun resolve(rule: SmartRule): SmartResult

    /** A saved one, for the listener: a new draw each time. */
    suspend fun resolveSmartPlaylist(id: String): SmartResult
    suspend fun saveSmartPlaylist(name: String, rule: SmartRule, shared: Boolean): SmartPlaylistInfo
    suspend fun updateSmartPlaylist(id: String, name: String, rule: SmartRule, shared: Boolean): SmartPlaylistInfo
    suspend fun deleteSmartPlaylist(id: String)

    /** Makes what it holds now into an ordinary playlist. */
    suspend fun freezeSmartPlaylist(id: String, name: String?)
}

// endregion

/**
 * Concerts: a whole concert as one song, with a chapter where each song of the show starts. On
 * iOS these were MusicSource methods with "unsupported" defaults; here only own.audio has them.
 */
interface ConcertSource : MusicSource {
    /** Whether the server has them; screens hide Concerts otherwise. */
    suspend fun supportsConcerts(): Boolean
    suspend fun concerts(): List<Album>

    /** A song's chapters in order; empty for an ordinary song. */
    suspend fun chapters(songId: String): List<SongChapter>

    /** Replaces a song's chapters (its owner's); answers them as stored. */
    suspend fun saveChapters(songId: String, chapters: List<SongChapter>): List<SongChapter>

    /** Where the listener stopped in a long song, kept by the server for every device. */
    suspend fun savedPosition(songId: String): Long?
    suspend fun savePosition(songId: String, positionMs: Long)
}

/** What a server's identify offers; null from [IdentifySource.identifyAvailability] when it has none. */
data class IdentifyAvailability(
    /** The server charges for identifying (its `billing` module): quote, then ask before starting. */
    val charges: Boolean,
)

/**
 * Identify (MusicBrainz): the server matches the listener's own songs in the background and
 * proposes what they are; nothing changes until a proposal is accepted, and no file ever does.
 * Runs and their proposals are [IdentifyRuns] and [IdentifyRunReview]; a single song is
 * identified here.
 */
interface IdentifySource : MusicSource {
    val identifyClient: OwnAudioClient

    /** Item ids as the API knows them (no server prefix), for building an identify scope. */
    fun apiId(id: String): String

    /**
     * Asked once and remembered while the source lives; a failed ask is not remembered. Null
     * when the server has no identify (or its `/api/v2` is too old for it).
     */
    suspend fun identifyAvailability(): IdentifyAvailability?

    /** The run list, and starting a run. */
    fun identifyRuns(availability: IdentifyAvailability): IdentifyRuns

    /** One run's proposals. */
    fun identifyRun(runId: String): IdentifyRunReview

    /** A run over one album or one artist, by their composite ids. */
    fun albumScope(albumId: String, label: String): IdentifyScope
    fun artistScope(artistId: String, label: String): IdentifyScope

    /** MusicBrainz recordings for one song, in the owner's words; blank words are left out. */
    suspend fun searchRecordings(songId: String, title: String, artist: String, album: String): RecordingResults

    /** Identifies one song as a recording, by its id or a musicbrainz.org link. */
    suspend fun identifySong(songId: String, recording: String, release: String? = null)

    /** Puts one song back as its file says; false when it was never identified (hide Undo). */
    suspend fun undoIdentify(songId: String): Boolean
}

/** Within five seconds of the song's own length, as the server's matching counts it. */
fun RecordingCandidate.lengthFits(songMs: Long?): Boolean {
    val ms = durationMs ?: return true
    if (songMs == null) return true
    return kotlin.math.abs(ms / 1000 - songMs / 1000) <= 5
}
