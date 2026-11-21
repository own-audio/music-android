package audio.own.music.library.ownaudio

import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.kit.ownaudio.Rfc3339
import audio.own.music.library.db.AlbumCreditRow
import audio.own.music.library.db.AlbumRow
import audio.own.music.library.db.ArtistRow
import audio.own.music.library.db.CatalogueDao
import audio.own.music.library.db.FavouriteRow
import audio.own.music.library.db.GenreRow
import audio.own.music.library.db.NamedCredit
import audio.own.music.library.db.PlaylistRow
import audio.own.music.library.db.RatingRow
import audio.own.music.library.db.TrackCreditRow
import audio.own.music.library.db.TrackGenreRow
import audio.own.music.library.db.TrackRow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import audio.own.kit.ownaudio.Album as ApiAlbum
import audio.own.kit.ownaudio.Contributor as ApiContributor
import audio.own.kit.ownaudio.Genre as ApiGenre
import audio.own.kit.ownaudio.Playlist as ApiPlaylist
import audio.own.kit.ownaudio.Track as ApiTrack

/** One record from the feed: its new state, or its removal ([record] null). */
data class FeedItem(val kind: String, val id: String, val record: JsonObject?)

/**
 * Writes feed records into the catalogue. Run it inside a Room transaction; it holds name caches,
 * so make one per sync, not per batch.
 *
 * The snapshot sends contributors, then albums, genres, tracks (the server's fixed order), so
 * the names a credit line needs are written before the albums and tracks that need them. Changes
 * come in any order, so with [changes] a renamed contributor, album or genre also rewrites the
 * lines and titles copied onto what refers to it.
 */
class CatalogueWriter(
    private val dao: CatalogueDao,
    private val serverId: String,
    private val gen: Long,
    private val changes: Boolean,
) {
    private val contributorNames = HashMap<String, String>()
    private val albumTitles = HashMap<String, String>()
    private val genreNames = HashMap<String, String>()

    /** Applies [items] in order; consecutive records of one kind are written together. */
    suspend fun apply(items: List<FeedItem>): Int {
        var written = 0
        var start = 0
        while (start < items.size) {
            val kind = items[start].kind
            val deleting = items[start].record == null
            var end = start
            while (end < items.size && items[end].kind == kind && (items[end].record == null) == deleting) end++
            val run = items.subList(start, end)
            if (deleting) delete(kind, run.map { it.id }) else upsert(kind, run)
            written += run.size
            start = end
        }
        return written
    }

    private suspend fun upsert(kind: String, run: List<FeedItem>) {
        when {
            kind == "contributor" -> contributors(decodeAll(run, ApiContributor.serializer()))
            kind == "album" -> albums(decodeAll(run, ApiAlbum.serializer()))
            kind == "genre" -> genres(decodeAll(run, ApiGenre.serializer()))
            kind == "track" -> tracks(decodeAll(run, ApiTrack.serializer()))
            kind == "playlist" -> dao.upsertPlaylists(decodeAll(run, ApiPlaylist.serializer()).map(::playlistRow))
            kind.startsWith("favourite.") -> dao.upsertFavourites(
                run.map { FavouriteRow(serverId, kind.substringAfter('.'), it.id, time(it.record, "created_at"), gen) },
            )
            kind.startsWith("rating.") -> {
                val (set, cleared) = run.partition { ((it.record?.get("rating") as? JsonPrimitive)?.intOrNull ?: 0) > 0 }
                dao.upsertRatings(
                    set.map {
                        RatingRow(serverId, kind.substringAfter('.'), it.id, (it.record!!["rating"] as JsonPrimitive).intOrNull!!, time(it.record, "updated_at"), gen)
                    },
                )
                for (item in cleared) dao.deleteRating(serverId, kind.substringAfter('.'), item.id)
            }
        }
    }

    private suspend fun delete(kind: String, ids: List<String>) {
        for (chunk in ids.chunked(CHUNK)) {
            when {
                kind == "contributor" -> {
                    dao.deleteArtists(serverId, chunk)
                    chunk.forEach(contributorNames::remove)
                }
                kind == "album" -> {
                    dao.deleteAlbums(serverId, chunk)
                    dao.deleteAlbumCredits(serverId, chunk)
                    chunk.forEach(albumTitles::remove)
                }
                kind == "genre" -> dao.deleteGenres(serverId, chunk)
                kind == "track" -> {
                    dao.deleteTracks(serverId, chunk)
                    dao.deleteTrackCredits(serverId, chunk)
                    dao.deleteTrackGenres(serverId, chunk)
                }
                kind == "playlist" -> {
                    dao.deletePlaylists(serverId, chunk)
                    for (id in chunk) dao.deletePlaylistEntries(serverId, id)
                }
                kind.startsWith("favourite.") -> for (id in chunk) dao.deleteFavourite(serverId, kind.substringAfter('.'), id)
                kind.startsWith("rating.") -> for (id in chunk) dao.deleteRating(serverId, kind.substringAfter('.'), id)
            }
        }
    }

    private suspend fun contributors(records: List<ApiContributor>) {
        val renamed = if (changes) {
            val before = records.map { it.id }.chunked(CHUNK).flatMap { dao.artistNames(serverId, it) }.associate { it.id to it.name }
            records.filter { before[it.id] != null && before[it.id] != it.name }
        } else {
            emptyList()
        }
        dao.upsertArtists(records.map { ArtistRow(serverId, it.id, it.name, it.sortName, it.image, gen) })
        for (c in records) contributorNames[c.id] = c.name
        for (c in renamed) {
            for (albumId in dao.albumsCrediting(serverId, c.id)) relineAlbum(albumId)
            for (trackId in dao.tracksCrediting(serverId, c.id)) relineTrack(trackId)
        }
    }

    private suspend fun albums(records: List<ApiAlbum>) {
        knowNames(records.flatMap { a -> a.credits.map { it.contributorId } })
        val retitled = if (changes) {
            val before = records.map { it.id }.chunked(CHUNK).flatMap { dao.albumTitles(serverId, it) }.associate { it.id to it.name }
            records.filter { before[it.id] != null && before[it.id] != it.title }
        } else {
            emptyList()
        }
        for (chunk in records.chunked(CHUNK)) dao.deleteAlbumCredits(serverId, chunk.map { it.id })
        dao.upsertAlbums(
            records.map { a ->
                AlbumRow(
                    serverId = serverId,
                    id = a.id,
                    title = a.title,
                    artistLine = RecordMapper.creditLine(a.credits, RecordMapper.ALBUM_ROLES, contributorNames::get),
                    artistId = RecordMapper.firstArtist(a.credits),
                    year = RecordMapper.year(a.releaseDate),
                    releaseDate = a.releaseDate,
                    releaseGroupId = a.releaseGroupId,
                    isCompilation = a.isCompilation,
                    trackCount = a.trackCount.toInt(),
                    durationMs = a.durationMs,
                    discCount = a.discCount,
                    image = a.image,
                    createdAtMs = a.createdAt.toEpochMilli(),
                    gen = gen,
                )
            },
        )
        dao.upsertAlbumCredits(
            records.flatMap { a -> a.credits.mapIndexed { i, c -> AlbumCreditRow(serverId, a.id, i, c.contributorId, c.role, c.joinPhrase, c.creditedAs, gen) } },
        )
        for (a in records) albumTitles[a.id] = a.title
        for (a in retitled) dao.setAlbumTitleOnTracks(serverId, a.id, a.title)
    }

    private suspend fun genres(records: List<ApiGenre>) {
        val renamed = if (changes) {
            val before = records.map { it.id }.chunked(CHUNK).flatMap { dao.genreNames(serverId, it) }.associate { it.id to it.name }
            records.filter { before[it.id] != null && before[it.id] != it.name }
        } else {
            emptyList()
        }
        dao.upsertGenres(records.map { GenreRow(serverId, it.id, it.name, gen) })
        for (g in records) genreNames[g.id] = g.name
        for (g in renamed) dao.setGenreNameOnTracks(serverId, g.id, g.name)
    }

    private suspend fun tracks(records: List<ApiTrack>) {
        knowNames(records.flatMap { t -> t.credits.map { it.contributorId } })
        knowAlbumTitles(records.mapNotNull { it.albumId })
        knowGenreNames(records.mapNotNull { it.genreIds.firstOrNull() })
        for (chunk in records.chunked(CHUNK)) {
            val ids = chunk.map { it.id }
            dao.deleteTrackCredits(serverId, ids)
            dao.deleteTrackGenres(serverId, ids)
        }
        dao.upsertTracks(records.map(::trackRow))
        dao.upsertTrackCredits(
            records.flatMap { t -> t.credits.mapIndexed { i, c -> TrackCreditRow(serverId, t.id, i, c.contributorId, c.role, c.joinPhrase, c.creditedAs, gen) } },
        )
        dao.upsertTrackGenres(records.flatMap { t -> t.genreIds.distinct().mapIndexed { i, g -> TrackGenreRow(serverId, t.id, g, i, t.albumId, gen) } })
    }

    private fun trackRow(t: ApiTrack) = TrackRow(
        serverId = serverId,
        id = t.id,
        title = t.title,
        albumId = t.albumId,
        albumTitle = t.albumId?.let(albumTitles::get),
        artistLine = RecordMapper.creditLine(t.credits, RecordMapper.TRACK_ROLES, contributorNames::get),
        artistId = RecordMapper.firstArtist(t.credits),
        discNumber = t.discNumber,
        trackNumber = t.trackNumber,
        durationMs = t.durationMs,
        genreName = t.genreIds.firstOrNull()?.let(genreNames::get),
        contentType = t.contentType,
        sizeBytes = t.sizeBytes,
        image = t.image,
        ownerId = t.ownerId,
        visibility = t.visibility,
        chapterCount = t.chapterCount.toInt(),
        createdAtMs = t.createdAt.toEpochMilli(),
        updatedAtMs = t.updatedAt.toEpochMilli(),
        gen = gen,
    )

    private fun playlistRow(p: ApiPlaylist) = PlaylistRow(
        serverId, p.id, p.name, p.description, p.visibility, p.ownerId, p.trackCount.toInt(), p.durationMs, p.image, p.generated,
        p.updatedAt.toEpochMilli(), gen,
    )

    private suspend fun relineAlbum(albumId: String) {
        val credits = dao.albumCredits(serverId, albumId)
        dao.setAlbumLine(serverId, albumId, line(credits, RecordMapper.ALBUM_ROLES), credits.firstOrNull { it.role == "artist" }?.contributorId)
    }

    private suspend fun relineTrack(trackId: String) {
        val credits = dao.trackCredits(serverId, trackId)
        dao.setTrackLine(serverId, trackId, line(credits, RecordMapper.TRACK_ROLES), credits.firstOrNull { it.role == "artist" }?.contributorId)
    }

    private fun line(credits: List<NamedCredit>, roles: Set<String>): String? {
        val names = credits.associate { it.contributorId to it.name }
        return RecordMapper.line(credits.map { CreditPart(it.contributorId, it.role, it.joinPhrase, it.creditedAs) }, roles) { names[it] }
    }

    // Names not met in this sync yet are read from what earlier syncs wrote.

    private suspend fun knowNames(ids: List<String>) {
        val missing = ids.distinct().filter { it !in contributorNames }
        for (chunk in missing.chunked(CHUNK)) for (n in dao.artistNames(serverId, chunk)) contributorNames[n.id] = n.name
    }

    private suspend fun knowAlbumTitles(ids: List<String>) {
        val missing = ids.distinct().filter { it !in albumTitles }
        for (chunk in missing.chunked(CHUNK)) for (n in dao.albumTitles(serverId, chunk)) albumTitles[n.id] = n.name
    }

    private suspend fun knowGenreNames(ids: List<String>) {
        val missing = ids.distinct().filter { it !in genreNames }
        for (chunk in missing.chunked(CHUNK)) for (n in dao.genreNames(serverId, chunk)) genreNames[n.id] = n.name
    }

    /** A record this version can't read is left out rather than failing the whole sync. */
    private fun <T> decodeAll(run: List<FeedItem>, serializer: KSerializer<T>): List<T> =
        run.mapNotNull { item -> item.record?.let { runCatching { OwnAudioJson.decodeFromJsonElement(serializer, it) }.getOrNull() } }

    private fun time(record: JsonObject?, key: String): Long =
        (record?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { runCatching { Rfc3339.parse(it).toEpochMilli() }.getOrNull() } ?: 0

    companion object {
        /** Under SQLite's limit on bound parameters in one statement. */
        const val CHUNK = 500

        /** The kinds the music catalogue keeps; the feed's others (books, progress, …) are not this app's. */
        fun keeps(kind: String): Boolean = kind in RECORD_KINDS || kind in STATE_KINDS

        private val RECORD_KINDS = setOf("contributor", "album", "genre", "track", "playlist")
        private val STATE_KINDS = setOf("favourite.track", "favourite.album", "favourite.contributor", "rating.track", "rating.album")
    }
}
