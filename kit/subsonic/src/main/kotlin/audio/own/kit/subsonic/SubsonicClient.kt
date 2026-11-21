package audio.own.kit.subsonic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.text.Collator
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Everything that can go wrong talking to a Subsonic server. */
sealed class SubsonicError(message: String) : Exception(message) {
    /** Error 40: wrong username or password. */
    data object WrongCredentials : SubsonicError("Wrong username or password")

    /**
     * Errors 41 and 42: the server doesn't take token sign-in (Ampache, LDAP accounts). Token
     * sign-in is the only one this client sends, so the password never travels in the clear.
     */
    data object TokenAuthUnsupported : SubsonicError("This server doesn't accept Subsonic sign-in with a token")

    /** Error 50: signed in, but this user may not do that. */
    data object NotAuthorized : SubsonicError("Not authorized")

    data class Http(val code: Int) : SubsonicError("HTTP $code")

    data class Server(val code: Int, val serverMessage: String) : SubsonicError("Subsonic error $code: $serverMessage")

    data object Decoding : SubsonicError("Unreadable answer")

    data object Network : SubsonicError("Network failure")

    /** Offline, and this wasn't saved from an earlier visit. */
    data object Offline : SubsonicError("Offline")
}

/**
 * A client for any Subsonic or OpenSubsonic server (Navidrome, Gonic, Airsonic, LMS, …).
 *
 * Auth is the token+salt scheme: `t = md5(password + salt)` with a fresh salt per request. Every
 * Subsonic server supports it, and the password never goes over the wire. The protocol has no
 * login step, so the password itself has to be kept by the app.
 *
 * @param cacheIdentity what saved answers are filed under instead of the address: a server reached
 *   at several addresses keeps one set of saved answers.
 * @param useFormPost send requests as POST forms (the OpenSubsonic `formPost` extension), so long
 *   parameter lists fit and the sign-in stays out of URLs. Player URLs stay GET regardless.
 */
class SubsonicClient(
    val host: HttpUrl,
    val username: String,
    private val password: String,
    private val http: OkHttpClient,
    private val userAgent: String,
    private val clientName: String = "own.audio Music",
    private val cache: ResponseCache? = null,
    private val isOffline: () -> Boolean = { false },
    private val cacheIdentity: String? = null,
    val useFormPost: Boolean = false,
    private val log: NetworkLog = NetworkLog.None,
) {
    /** The same client with form posting switched on or off, once the server's extensions are known. */
    fun withFormPost(enabled: Boolean): SubsonicClient = SubsonicClient(
        host, username, password, http, userAgent, clientName, cache, isOffline, cacheIdentity, enabled, log,
    )

    /** Three states and one more, so a mistyped password isn't reported as an outage. */
    suspend fun checkConnection(): ConnectionStatus = try {
        call("ping", serializer = Status.serializer())
        ConnectionStatus.Ok
    } catch (e: SubsonicError.WrongCredentials) {
        ConnectionStatus.Rejected
    } catch (e: SubsonicError.NotAuthorized) {
        ConnectionStatus.Rejected
    } catch (e: SubsonicError.TokenAuthUnsupported) {
        ConnectionStatus.Unsupported
    } catch (e: SubsonicError) {
        ConnectionStatus.Unreachable
    }

    /**
     * Which server this is, from `ping`. OpenSubsonic servers name themselves even when the
     * sign-in fails, so this also works before the password is known to be right.
     */
    suspend fun serverIdentity(): ServerIdentity = call("ping", serializer = ServerIdentity.serializer(), checkStatus = false)

    // Browsing

    suspend fun artists(): List<Artist> = call("getArtists", serializer = ArtistsPayload.serializer()).flattened

    suspend fun artist(id: String): Pair<Artist, List<Album>> {
        val detail = call("getArtist", listOf("id" to id), ArtistPayload.serializer()).artist
        val albums = detail.album.orEmpty()
        val artist = Artist(
            id = detail.id, name = detail.name, albumCount = albums.size,
            artistImageUrl = detail.artistImageUrl, starred = detail.starred,
        )
        return artist to albums
    }

    suspend fun albumList(type: AlbumListType = AlbumListType.AlphabeticalByArtist, size: Int = 500, offset: Int = 0): List<Album> =
        call(
            "getAlbumList2",
            listOf("type" to type.wireName, "size" to size.toString(), "offset" to offset.toString()),
            AlbumListPayload.serializer(),
        ).albumList2.album.orEmpty()

    /** One call stops at 500 (the protocol's maximum); a library of 905 albums showed only the first 500. */
    suspend fun allAlbums(): List<Album> {
        val albums = mutableListOf<Album>()
        while (true) {
            val page = albumList(size = MAX_PAGE, offset = albums.size)
            albums += page
            if (page.size < MAX_PAGE) return albums
        }
    }

    suspend fun albums(genre: String, size: Int = 60, offset: Int = 0): List<Album> = call(
        "getAlbumList2",
        listOf("type" to "byGenre", "genre" to genre, "size" to size.toString(), "offset" to offset.toString()),
        AlbumListPayload.serializer(),
    ).albumList2.album.orEmpty()

    /** Albums from [fromYear] to [toYear]; a later year first lists newest first. */
    suspend fun albums(fromYear: Int, toYear: Int, size: Int = 60, offset: Int = 0): List<Album> = call(
        "getAlbumList2",
        listOf(
            "type" to "byYear", "fromYear" to fromYear.toString(), "toYear" to toYear.toString(),
            "size" to size.toString(), "offset" to offset.toString(),
        ),
        AlbumListPayload.serializer(),
    ).albumList2.album.orEmpty()

    suspend fun album(id: String): Pair<Album, List<Song>> {
        val detail = call("getAlbum", listOf("id" to id), AlbumPayload.serializer()).album
        val songs = detail.song.orEmpty()
        val album = Album(
            id = detail.id, name = detail.name, artist = detail.artist, artistId = detail.artistId,
            songCount = songs.size, duration = detail.duration, coverArt = detail.coverArt, year = detail.year,
            starred = detail.starred, userRating = detail.userRating, playCount = detail.playCount,
        )
        return album to songs
    }

    suspend fun song(id: String): Song = call("getSong", listOf("id" to id), SongPayload.serializer()).song

    suspend fun search(query: String, limit: Int = 40): SearchResult {
        val result = call(
            "search3",
            listOf(
                "query" to query, "artistCount" to limit.toString(),
                "albumCount" to limit.toString(), "songCount" to limit.toString(),
            ),
            SearchPayload.serializer(),
        ).searchResult3
        return SearchResult(result.artist.orEmpty(), result.album.orEmpty(), result.song.orEmpty())
    }

    suspend fun genres(): List<Genre> {
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        return call("getGenres", serializer = GenresPayload.serializer()).genres.genre.orEmpty()
            .sortedWith { a, b -> collator.compare(a.value, b.value) }
    }

    /**
     * Every song, a page at a time. Subsonic has no "all songs" call; an empty `search3` query is
     * the OpenSubsonic way. A server that answers it with nothing needs walking album by album.
     */
    suspend fun songs(size: Int = 100, offset: Int = 0): List<Song> = call(
        "search3",
        listOf(
            "query" to "", "songCount" to size.toString(), "songOffset" to offset.toString(),
            "artistCount" to "0", "albumCount" to "0",
        ),
        SearchPayload.serializer(),
    ).searchResult3.song.orEmpty()

    suspend fun songs(genre: String, count: Int = 100, offset: Int = 0): List<Song> = call(
        "getSongsByGenre",
        listOf("genre" to genre, "count" to count.toString(), "offset" to offset.toString()),
        SongsByGenrePayload.serializer(),
    ).songsByGenre.song.orEmpty()

    suspend fun randomSongs(size: Int = 100, genre: String? = null): List<Song> {
        val query = buildList {
            add("size" to size.toString())
            if (genre != null) add("genre" to genre)
        }
        return call("getRandomSongs", query, RandomSongsPayload.serializer()).randomSongs.song.orEmpty()
    }

    // Playlists

    suspend fun playlists(): List<Playlist> =
        call("getPlaylists", serializer = PlaylistsPayload.serializer()).playlists.playlist.orEmpty()

    suspend fun playlist(id: String): Pair<Playlist, List<Song>> {
        val p = call("getPlaylist", listOf("id" to id), PlaylistPayload.serializer()).playlist
        return Playlist(p.id, p.name, p.comment, p.owner, p.songCount, p.duration, p.coverArt) to p.entry.orEmpty()
    }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): Playlist {
        val query = listOf("name" to name) + songIds.map { "songId" to it }
        return call("createPlaylist", query, CreatePlaylistPayload.serializer()).playlist
    }

    /**
     * The server applies additions first and then removes by index against the resulting order,
     * so pass either additions or removals, not both, and reload afterwards.
     */
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        comment: String? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndicesToRemove: List<Int> = emptyList(),
    ) {
        val query = buildList {
            add("playlistId" to id)
            if (name != null) add("name" to name)
            if (comment != null) add("comment" to comment)
            songIdsToAdd.forEach { add("songIdToAdd" to it) }
            songIndicesToRemove.forEach { add("songIndexToRemove" to it.toString()) }
        }
        call("updatePlaylist", query, Status.serializer())
    }

    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", listOf("id" to id), Status.serializer())
    }

    // Favorites, ratings, scrobbling

    suspend fun starred(): StarredResult {
        val s = call("getStarred2", serializer = StarredPayload.serializer()).starred2
        return StarredResult(s.artist.orEmpty(), s.album.orEmpty(), s.song.orEmpty())
    }

    suspend fun star(songIds: List<String> = emptyList(), albumIds: List<String> = emptyList(), artistIds: List<String> = emptyList()) {
        call("star", starQuery(songIds, albumIds, artistIds), Status.serializer())
    }

    suspend fun unstar(songIds: List<String> = emptyList(), albumIds: List<String> = emptyList(), artistIds: List<String> = emptyList()) {
        call("unstar", starQuery(songIds, albumIds, artistIds), Status.serializer())
    }

    /** [rating] 1–5, or 0 to clear it. Works for songs and albums alike. */
    suspend fun setRating(id: String, rating: Int) {
        call("setRating", listOf("id" to id, "rating" to rating.coerceIn(0, 5).toString()), Status.serializer())
    }

    /**
     * `submission = false` is "now playing"; `true` counts a play. [time] is when listening
     * started, which matters for plays submitted late (after being offline).
     */
    suspend fun scrobble(songId: String, submission: Boolean, time: Instant? = null) {
        val query = buildList {
            add("id" to songId)
            add("submission" to submission.toString())
            if (time != null) add("time" to time.toEpochMilli().toString())
        }
        call("scrobble", query, Status.serializer())
    }

    /**
     * The OpenSubsonic extensions the server supports, by name. Empty for a classic Subsonic
     * server, which answers this endpoint with an error.
     */
    suspend fun openSubsonicExtensions(): Set<String> = try {
        call("getOpenSubsonicExtensions", serializer = ExtensionsPayload.serializer())
            .openSubsonicExtensions.orEmpty().map { it.name }.toSet()
    } catch (e: SubsonicError) {
        emptySet()
    }

    private fun starQuery(songIds: List<String>, albumIds: List<String>, artistIds: List<String>) =
        songIds.map { "id" to it } + albumIds.map { "albumId" to it } + artistIds.map { "artistId" to it }

    // Lyrics

    /**
     * Lyrics for a song: the OpenSubsonic `songLyrics` extension when the server has it (synced
     * lines, several languages), otherwise classic `getLyrics` by artist and title (plain text).
     * Empty when there are none.
     */
    suspend fun lyrics(song: Song, useSongLyricsExtension: Boolean): List<Lyrics> {
        if (useSongLyricsExtension) {
            val list = call("getLyricsBySongId", listOf("id" to song.id), StructuredLyricsPayload.serializer())
            return list.lyricsList.structuredLyrics.orEmpty().mapNotNull { item ->
                val lines = item.line.orEmpty().map { Lyrics.Line(it.start, it.value) }
                if (lines.isEmpty()) return@mapNotNull null
                val synced = (item.synced ?: false) && lines.any { it.startMs != null }
                Lyrics(lines, synced, item.lang, item.offset ?: 0)
            }
        }
        val artist = song.artist ?: return emptyList()
        val payload = call("getLyrics", listOf("artist" to artist, "title" to song.title), ClassicLyricsPayload.serializer())
        val text = payload.lyrics?.value?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return listOf(Lyrics(text.lines().map { Lyrics.Line(null, it) }, isSynced = false))
    }

    // URLs for the player, downloads and image loading. Auth is computed locally, so these need no request first.

    /**
     * The original, or with [maxBitRate] (kbps) a file in [format] the server makes on the fly. A
     * server that doesn't transcode, or whose original already fits, sends the original.
     * [timeOffsetSecs] starts a transcode part-way in; only for servers with `transcodeOffset`.
     */
    fun streamUrl(songId: String, maxBitRate: Int? = null, format: String = "mp3", timeOffsetSecs: Int? = null): HttpUrl {
        val query = buildList {
            add("id" to songId)
            if (maxBitRate != null) {
                add("maxBitRate" to maxBitRate.toString())
                add("format" to format)
            }
            if (timeOffsetSecs != null) add("timeOffset" to timeOffsetSecs.toString())
        }
        return buildUrl("stream", query)
    }

    /** The original file, never transcoded. */
    fun downloadUrl(songId: String): HttpUrl = buildUrl("download", listOf("id" to songId))

    fun coverUrl(id: String, size: Int? = null): HttpUrl {
        val query = buildList {
            add("id" to id)
            if (size != null) add("size" to size.toString())
        }
        return buildUrl("getCoverArt", query)
    }

    // Transport

    /** The request without the per-request salt and token, so the same question maps to the same saved answer. */
    internal fun cacheKey(path: String, query: List<Pair<String, String>>): String {
        val params = query.map { "${it.first}=${it.second}" }.sorted().joinToString("&")
        return "${cacheIdentity ?: host.toString()}|$username|$path?$params"
    }

    /**
     * Offline, a saved answer or [SubsonicError.Offline] without touching the network. Online, a
     * network failure falls back to the saved answer, so a connection that is there but goes
     * nowhere still shows what was seen before.
     */
    private suspend fun <T> call(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        serializer: KSerializer<T>,
        checkStatus: Boolean = true,
    ): T {
        val key = if (cache != null && path in CACHED_ACTIONS) cacheKey(path, query) else null
        fun saved() = key?.let { cache?.data(it) }

        if (isOffline()) {
            val data = saved() ?: throw SubsonicError.Offline
            return decode(data, serializer, checkStatus)
        }

        val answer = try {
            http.newCall(request(path, query)).awaitAnswer()
        } catch (e: IOException) {
            log.error("$path failed: ${e.javaClass.simpleName}")
            val data = saved() ?: throw SubsonicError.Network
            return decode(data, serializer, checkStatus)
        }
        if (answer.code !in 200..299) {
            log.error("$path: HTTP ${answer.code}")
            throw SubsonicError.Http(answer.code)
        }
        val decoded = decode(answer.body, serializer, checkStatus)
        if (key != null) cache?.store(key, answer.body)
        return decoded
    }

    private fun request(path: String, query: List<Pair<String, String>>): Request {
        val builder = Request.Builder().header("User-Agent", userAgent)
        if (!useFormPost) return builder.url(buildUrl(path, query)).build()
        val form = FormBody.Builder()
        (authQuery() + query).forEach { (name, value) -> form.add(name, value) }
        return builder.url(endpoint(path).build()).post(form.build()).build()
    }

    private fun <T> decode(data: ByteArray, serializer: KSerializer<T>, checkStatus: Boolean): T {
        val response = try {
            json.parseToJsonElement(data.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        }?.get("subsonic-response") as? JsonObject
        if (response == null) {
            log.error("unreadable answer")
            throw SubsonicError.Decoding
        }
        // Subsonic reports failures, including bad credentials, as HTTP 200 with
        // `status: "failed"` in the body.
        if (checkStatus && response.string("status") == "failed") {
            val failure = runCatching { json.decodeFromJsonElement(Failure.serializer(), response) }.getOrNull()?.error
            val code = failure?.code ?: 0
            throw when (code) {
                // Ampache answers every token sign-in with 40, right password or not: it takes
                // only a plain password or an API key.
                40 -> if (response.string("type") == "ampache") SubsonicError.TokenAuthUnsupported else SubsonicError.WrongCredentials
                41, 42 -> SubsonicError.TokenAuthUnsupported
                50 -> SubsonicError.NotAuthorized
                else -> SubsonicError.Server(code, failure?.message.orEmpty())
            }
        }
        return try {
            json.decodeFromJsonElement(serializer, response)
        } catch (e: SerializationException) {
            log.error("unreadable answer as ${serializer.descriptor.serialName}: ${e.whereItFailed()}")
            throw SubsonicError.Decoding
        } catch (e: IllegalArgumentException) {
            log.error("unreadable answer as ${serializer.descriptor.serialName}")
            throw SubsonicError.Decoding
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun endpoint(path: String): HttpUrl.Builder =
        host.newBuilder().addPathSegment("rest").addPathSegment("$path.view")

    private fun buildUrl(path: String, query: List<Pair<String, String>>): HttpUrl {
        val builder = endpoint(path)
        (authQuery() + query).forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun authQuery(): List<Pair<String, String>> {
        val salt = UUID.randomUUID().toString().replace("-", "").lowercase()
        return listOf(
            "u" to username,
            "t" to md5Hex(password + salt),
            "s" to salt,
            "v" to API_VERSION,
            "c" to clientName,
            "f" to "json",
        )
    }

    companion object {
        /** Servers use the version only to decide which optional fields to send; none reject an older one. */
        const val API_VERSION = "1.16.1"

        private const val MAX_PAGE = 500

        /**
         * Reads whose answers are kept for offline use. Search and random picks are left out: an
         * old answer to those would be misleading rather than useful.
         */
        internal val CACHED_ACTIONS = setOf(
            "getArtists", "getArtist", "getAlbumList2", "getAlbum", "getSong", "getGenres",
            "getPlaylists", "getPlaylist", "getStarred2", "getLyrics", "getLyricsBySongId",
            "getOpenSubsonicExtensions", "getSongsByGenre",
        )

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /**
         * Accepts "host", "host:port" or a full URL. Defaults to http because most home servers
         * have no TLS on the local network; an explicit https:// is kept.
         */
        fun normalizeHost(rawInput: String): HttpUrl? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            return withScheme.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() }
        }

        internal fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
