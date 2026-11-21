package audio.own.kit.jellyfin

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * How this app introduces itself. Jellyfin lists sessions by device, so [deviceId] has to stay
 * the same across launches; the app keeps one. [userAgent] goes on every request as well.
 */
data class ClientInfo(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String,
    val userAgent: String = "$client/$version",
) {
    /** The `Authorization: MediaBrowser …` value. Values are quoted, so quotes in them go. */
    fun header(token: String?): String {
        fun clean(value: String) = value.replace("\"", "")
        val parts = buildList {
            add("Client=\"${clean(client)}\"")
            add("Device=\"${clean(device)}\"")
            add("DeviceId=\"${clean(deviceId)}\"")
            add("Version=\"${clean(version)}\"")
            if (token != null) add("Token=\"${clean(token)}\"")
        }
        return "MediaBrowser " + parts.joinToString(", ")
    }
}

/** Everything that can go wrong talking to a Jellyfin server. */
sealed class JellyfinError(message: String) : Exception(message) {
    /** The token is no longer accepted (signed out elsewhere, or the user was removed), or the password is wrong. */
    data object Unauthorized : JellyfinError("Unauthorized")

    data object Forbidden : JellyfinError("Forbidden")

    data object NotFound : JellyfinError("Not found")

    data class Http(val code: Int) : JellyfinError("HTTP $code")

    data object Decoding : JellyfinError("Unreadable answer")

    data object Network : JellyfinError("Network failure")

    /** Offline, and this wasn't saved from an earlier visit. */
    data object Offline : JellyfinError("Offline")
}

/**
 * A client for a Jellyfin server's music. Written by hand against the REST API: a music player
 * needs a few dozen endpoints, and a client generated from the full API would mostly be code for
 * video, TV and administration.
 *
 * @param libraryId restricts listings to one music library; null means all of them.
 * @param cacheIdentity what saved answers are filed under instead of the address: a server
 *   reached at several addresses keeps one set of saved answers.
 */
class JellyfinClient(
    val host: HttpUrl,
    val session: Session,
    private val clientInfo: ClientInfo,
    private val http: OkHttpClient,
    val libraryId: String? = null,
    private val cache: ResponseCache? = null,
    private val isOffline: () -> Boolean = { false },
    private val cacheIdentity: String? = null,
    private val log: NetworkLog = NetworkLog.None,
) {
    // Session

    /** Whether the saved token still works. */
    suspend fun checkSession(): ConnectionStatus = try {
        get("Users/Me", cached = false, serializer = JsonObject.serializer())
        ConnectionStatus.Ok
    } catch (e: JellyfinError.Unauthorized) {
        ConnectionStatus.Rejected
    } catch (e: JellyfinError.Forbidden) {
        ConnectionStatus.Rejected
    } catch (e: JellyfinError) {
        ConnectionStatus.Unreachable
    }

    /** Ends the session on the server, so it disappears from the dashboard's device list. */
    suspend fun signOut() {
        try {
            send("POST", "Sessions/Logout")
        } catch (e: JellyfinError) {
            // Best effort: the server being gone is no reason to keep the server in the app.
        }
    }

    // Browsing

    suspend fun musicLibraries(): List<JellyfinLibrary> =
        get("UserViews", mapOf("userId" to session.userId), serializer = ItemsResponse.serializer()).items
            .filter { it.collectionType == "music" }
            .map { JellyfinLibrary(it.id, it.name.orEmpty()) }

    /** Album artists, the way music apps list artists: one entry per artist with albums. */
    suspend fun artists(): List<Artist> = get(
        "Artists/AlbumArtists",
        libraryQuery(
            mapOf(
                "userId" to session.userId, "SortBy" to "SortName", "Fields" to "ChildCount",
                "EnableUserData" to "true", "EnableImageTypes" to "Primary",
            ),
        ),
        serializer = ItemsResponse.serializer(),
    ).items.map { it.asArtist() }

    suspend fun albums(sort: AlbumSort, limit: Int = 60, offset: Int = 0): List<Album> {
        val query = albumQuery(limit, offset).toMutableMap()
        when (sort) {
            AlbumSort.Name -> query["SortBy"] = "SortName"
            AlbumSort.Artist -> query["SortBy"] = "AlbumArtist,SortName"
            AlbumSort.Newest -> {
                query["SortBy"] = "DateCreated,SortName"
                query["SortOrder"] = "Descending"
            }
            AlbumSort.MostPlayed -> {
                query["SortBy"] = "PlayCount,SortName"
                query["SortOrder"] = "Descending"
            }
            AlbumSort.Random -> query["SortBy"] = "Random"
            AlbumSort.Favorites -> {
                query["SortBy"] = "SortName"
                query["Filters"] = "IsFavorite"
            }
            AlbumSort.RecentlyPlayed -> return recentlyPlayedAlbums(limit)
        }
        return get("Items", query, cached = sort != AlbumSort.Random, serializer = ItemsResponse.serializer()).items.map { it.asAlbum() }
    }

    /**
     * Albums don't record when they were last played; their songs do. So this reads recently
     * played songs and keeps the first appearance of each album.
     */
    private suspend fun recentlyPlayedAlbums(limit: Int): List<Album> {
        val played = get(
            "Items",
            libraryQuery(
                mapOf(
                    "userId" to session.userId, "IncludeItemTypes" to "Audio", "Recursive" to "true",
                    "Filters" to "IsPlayed", "SortBy" to "DatePlayed", "SortOrder" to "Descending",
                    "Limit" to (limit * 5).toString(), "Fields" to "ParentId",
                ),
            ),
            serializer = ItemsResponse.serializer(),
        )
        val albumIds = played.items.mapNotNull { it.albumId }.distinct().take(limit)
        if (albumIds.isEmpty()) return emptyList()
        val albums = get(
            "Items",
            mapOf(
                "userId" to session.userId, "Ids" to albumIds.joinToString(","),
                "Fields" to ALBUM_FIELDS, "EnableUserData" to "true",
            ),
            serializer = ItemsResponse.serializer(),
        )
        val byId = albums.items.associate { it.id to it.asAlbum() }
        return albumIds.mapNotNull { byId[it] }
    }

    suspend fun albums(artistId: String): List<Album> {
        val query = albumQuery(null, 0) + mapOf("AlbumArtistIds" to artistId, "SortBy" to "ProductionYear,SortName")
        return get("Items", query, serializer = ItemsResponse.serializer()).items.map { it.asAlbum() }
    }

    suspend fun albums(genreId: String, limit: Int = 200): List<Album> {
        val query = albumQuery(limit, 0) + mapOf("GenreIds" to genreId, "SortBy" to "SortName")
        return get("Items", query, serializer = ItemsResponse.serializer()).items.map { it.asAlbum() }
    }

    /** An album and its songs in disc and track order. */
    suspend fun album(id: String): Pair<Album, List<Song>> = coroutineScope {
        val item = async {
            get("Items/$id", mapOf("userId" to session.userId, "Fields" to ALBUM_FIELDS), serializer = ItemDto.serializer())
        }
        val tracks = async {
            get(
                "Items",
                mapOf(
                    "userId" to session.userId, "ParentId" to id, "IncludeItemTypes" to "Audio",
                    "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Fields" to SONG_FIELDS,
                    "EnableUserData" to "true",
                ),
                serializer = ItemsResponse.serializer(),
            )
        }
        item.await().asAlbum() to tracks.await().items.map { it.asSong() }
    }

    suspend fun song(id: String): Song =
        get("Items/$id", mapOf("userId" to session.userId, "Fields" to SONG_FIELDS), serializer = ItemDto.serializer()).asSong()

    /** Every song, a page at a time, by title. */
    suspend fun songs(limit: Int = 100, offset: Int = 0): List<Song> = get(
        "Items",
        songQuery(mapOf("SortBy" to "SortName", "Limit" to limit.toString(), "StartIndex" to offset.toString())),
        serializer = ItemsResponse.serializer(),
    ).items.map { it.asSong() }

    suspend fun randomSongs(limit: Int = 100, genreId: String? = null): List<Song> {
        val extra = buildMap {
            put("SortBy", "Random")
            put("Limit", limit.toString())
            if (genreId != null) put("GenreIds", genreId)
        }
        return get("Items", songQuery(extra), cached = false, serializer = ItemsResponse.serializer()).items.map { it.asSong() }
    }

    suspend fun genres(): List<Genre> = get(
        "MusicGenres",
        libraryQuery(mapOf("userId" to session.userId, "SortBy" to "SortName", "Fields" to "ItemCounts")),
        serializer = ItemsResponse.serializer(),
    ).items.map { it.asGenre() }

    /** Artists, albums and songs matching [text], one request per kind so each gets its own share of results. */
    suspend fun search(text: String, limit: Int = 20): SearchResult = coroutineScope {
        val artists = async {
            get(
                "Artists/AlbumArtists",
                libraryQuery(
                    mapOf(
                        "userId" to session.userId, "SearchTerm" to text, "Limit" to limit.toString(),
                        "EnableImageTypes" to "Primary",
                    ),
                ),
                cached = false, serializer = ItemsResponse.serializer(),
            )
        }
        val albums = async {
            get("Items", albumQuery(limit, 0) + ("SearchTerm" to text), cached = false, serializer = ItemsResponse.serializer())
        }
        val songs = async {
            get(
                "Items", songQuery(mapOf("SearchTerm" to text, "Limit" to limit.toString())),
                cached = false, serializer = ItemsResponse.serializer(),
            )
        }
        SearchResult(
            artists.await().items.map { it.asArtist() },
            albums.await().items.map { it.asAlbum() },
            songs.await().items.map { it.asSong() },
        )
    }

    /** Songs like this song, album or artist, picked by the server. */
    suspend fun instantMix(itemId: String, limit: Int = 100): List<Song> = get(
        "Items/$itemId/InstantMix",
        mapOf("userId" to session.userId, "Limit" to limit.toString(), "Fields" to SONG_FIELDS, "EnableUserData" to "true"),
        cached = false, serializer = ItemsResponse.serializer(),
    ).items.map { it.asSong() }

    // Favorites

    suspend fun favorites(): FavoritesResult = coroutineScope {
        val artists = async {
            get(
                "Artists/AlbumArtists",
                libraryQuery(
                    mapOf(
                        "userId" to session.userId, "IsFavorite" to "true", "SortBy" to "SortName",
                        "EnableUserData" to "true", "EnableImageTypes" to "Primary",
                    ),
                ),
                serializer = ItemsResponse.serializer(),
            )
        }
        val albums = async {
            get("Items", albumQuery(null, 0) + mapOf("Filters" to "IsFavorite", "SortBy" to "SortName"), serializer = ItemsResponse.serializer())
        }
        val songs = async {
            get("Items", songQuery(mapOf("Filters" to "IsFavorite", "SortBy" to "SortName")), serializer = ItemsResponse.serializer())
        }
        FavoritesResult(
            artists.await().items.map { it.asArtist() },
            albums.await().items.map { it.asAlbum() },
            songs.await().items.map { it.asSong() },
        )
    }

    /** Favorites are per user and work the same for songs, albums and artists. */
    suspend fun setFavorite(itemId: String, isFavorite: Boolean) {
        send(if (isFavorite) "POST" else "DELETE", "UserFavoriteItems/$itemId", mapOf("userId" to session.userId))
    }

    // Playlists

    /**
     * Every playlist but video ones. Not filtered to audio on the server: playlists imported from
     * .m3u files come with media type "Unknown", and those would be left out.
     */
    suspend fun playlists(): List<Playlist> = get(
        "Items",
        mapOf(
            "userId" to session.userId, "IncludeItemTypes" to "Playlist", "Recursive" to "true",
            "SortBy" to "SortName", "Fields" to "ChildCount",
        ),
        serializer = ItemsResponse.serializer(),
    ).items.filter { it.mediaType != "Video" }.map { it.asPlaylist() }

    suspend fun playlist(id: String): Pair<Playlist, List<Song>> = coroutineScope {
        val item = async {
            get("Items/$id", mapOf("userId" to session.userId, "Fields" to "ChildCount"), serializer = ItemDto.serializer())
        }
        val entries = async {
            get(
                "Playlists/$id/Items",
                mapOf("userId" to session.userId, "Fields" to SONG_FIELDS, "EnableUserData" to "true"),
                serializer = ItemsResponse.serializer(),
            )
        }
        item.await().asPlaylist() to entries.await().items.map { it.asSong() }
    }

    /** Returns the new playlist's id. */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String {
        val body = buildJsonObject {
            put("Name", name)
            putJsonArray("Ids") { songIds.forEach { add(it) } }
            put("UserId", session.userId)
            put("MediaType", "Audio")
        }
        return decode(send("POST", "Playlists", body = body), CreatedDto.serializer()).id
    }

    suspend fun addToPlaylist(id: String, songIds: List<String>) {
        send("POST", "Playlists/$id/Items", mapOf("ids" to songIds.joinToString(","), "userId" to session.userId))
    }

    /** By the entries' [Song.playlistItemId], not the song ids. */
    suspend fun removeFromPlaylist(id: String, entryIds: List<String>) {
        send("DELETE", "Playlists/$id/Items", mapOf("entryIds" to entryIds.joinToString(",")))
    }

    suspend fun renamePlaylist(id: String, name: String) {
        send("POST", "Playlists/$id", body = buildJsonObject { put("Name", name) })
    }

    suspend fun deletePlaylist(id: String) {
        send("DELETE", "Items/$id")
    }

    // Lyrics

    /** Null when the song has none. */
    suspend fun lyrics(songId: String): Lyrics? {
        val dto = try {
            get("Audio/$songId/Lyrics", serializer = LyricsDto.serializer())
        } catch (e: JellyfinError.NotFound) {
            return null
        }
        val lines = dto.lyrics.orEmpty().map { Lyrics.Line(it.start?.let { ticks -> (ticks / 10_000).toInt() }, it.text.orEmpty()) }
        if (lines.isEmpty()) return null
        return Lyrics(lines, isSynced = lines.any { it.startMs != null })
    }

    // Playback reporting

    /** Shows the song on the server's dashboard as playing on this device. */
    suspend fun reportStart(songId: String, positionSecs: Double = 0.0) {
        send("POST", "Sessions/Playing", body = playbackBody(songId, positionSecs, isPaused = false))
    }

    suspend fun reportProgress(songId: String, positionSecs: Double, isPaused: Boolean) {
        send("POST", "Sessions/Playing/Progress", body = playbackBody(songId, positionSecs, isPaused))
    }

    suspend fun reportStopped(songId: String, positionSecs: Double) {
        send("POST", "Sessions/Playing/Stopped", body = playbackBody(songId, positionSecs, isPaused = false))
    }

    /**
     * Counts a play: the play count goes up and "last played" moves. [at] is when it was heard,
     * for plays reported late after being offline.
     */
    suspend fun markPlayed(songId: String, at: Instant? = null) {
        val query = buildMap {
            put("userId", session.userId)
            if (at != null) put("datePlayed", DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS)))
        }
        send("POST", "UserPlayedItems/$songId", query)
    }

    private fun playbackBody(songId: String, positionSecs: Double, isPaused: Boolean) = buildJsonObject {
        put("ItemId", songId)
        put("PositionTicks", (positionSecs * 10_000_000).toLong())
        put("IsPaused", isPaused)
        put("CanSeek", true)
        put("PlayMethod", "DirectPlay")
    }

    // URLs for the player and image loading

    /**
     * The original file, or with [maxBitrate] (bits per second) an MP3 the server makes on the fly
     * at that rate, to save mobile data. MP3 because the player can decode it in pieces as it
     * arrives, and every server's ffmpeg can make it. The token goes in the query because the
     * player fetches the URL as is.
     */
    fun streamUrl(songId: String, maxBitrate: Int? = null): HttpUrl {
        if (maxBitrate == null) {
            return url("Audio/$songId/stream", mapOf("static" to "true", "ApiKey" to session.accessToken))
        }
        return url(
            "Audio/$songId/stream.mp3",
            mapOf(
                "static" to "false", "audioCodec" to "mp3", "audioBitRate" to maxBitrate.toString(),
                "ApiKey" to session.accessToken,
            ),
        )
    }

    /**
     * Jellyfin's own choice between the original and a transcode: the original when its container
     * is one of [containers] and it fits [maxBitrate] (bits per second), otherwise an MP3 at that
     * rate. The answer may be a redirect to the file.
     */
    fun universalUrl(songId: String, maxBitrate: Int, containers: List<String> = PLAYABLE_CONTAINERS): HttpUrl = url(
        "Audio/$songId/universal",
        mapOf(
            "UserId" to session.userId, "DeviceId" to clientInfo.deviceId,
            "MaxStreamingBitrate" to maxBitrate.toString(), "Container" to containers.joinToString(","),
            "TranscodingContainer" to "mp3", "TranscodingProtocol" to "http", "AudioCodec" to "mp3",
            "ApiKey" to session.accessToken,
        ),
    )

    /** Images need no sign-in on Jellyfin. [size] is in pixels; the server scales and crops. */
    fun imageUrl(itemId: String, size: Int): HttpUrl = url(
        "Items/$itemId/Images/Primary",
        mapOf("fillWidth" to size.toString(), "fillHeight" to size.toString(), "quality" to "90"),
    )

    // Queries

    private fun libraryQuery(query: Map<String, String>): Map<String, String> =
        if (libraryId == null) query else mapOf("ParentId" to libraryId) + query

    private fun albumQuery(limit: Int?, offset: Int): Map<String, String> {
        val query = libraryQuery(
            mapOf(
                "userId" to session.userId, "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true",
                "Fields" to ALBUM_FIELDS, "EnableUserData" to "true",
                "EnableImageTypes" to "Primary", "StartIndex" to offset.toString(),
            ),
        )
        return if (limit == null) query else query + ("Limit" to limit.toString())
    }

    private fun songQuery(extra: Map<String, String>): Map<String, String> = libraryQuery(
        mapOf(
            "userId" to session.userId, "IncludeItemTypes" to "Audio", "Recursive" to "true",
            "Fields" to SONG_FIELDS, "EnableUserData" to "true",
        ),
    ) + extra

    // Transport

    /** The request without the token, so the same question maps to the same saved answer. */
    internal fun cacheKey(path: String, query: Map<String, String>): String {
        val params = query.map { "${it.key}=${it.value}" }.sorted().joinToString("&")
        return "${cacheIdentity ?: host.toString()}|${session.userId}|$path?$params"
    }

    /**
     * GETs are saved for offline use unless [cached] is false (search, random picks: an old answer
     * to those misleads). Offline, a saved answer or [JellyfinError.Offline] without touching the
     * network; online, a failed request falls back to the saved answer.
     */
    private suspend fun <T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
        cached: Boolean = true,
        serializer: KSerializer<T>,
    ): T {
        val key = if (cached && cache != null) cacheKey(path, query) else null
        fun saved() = key?.let { cache?.data(it) }
        if (isOffline()) {
            val data = saved() ?: throw JellyfinError.Offline
            return decode(data, serializer)
        }
        val data = try {
            send("GET", path, query)
        } catch (e: JellyfinError.Network) {
            return decode(saved() ?: throw e, serializer)
        }
        val decoded = decode(data, serializer)
        if (key != null) cache?.store(key, data)
        return decoded
    }

    private suspend fun send(method: String, path: String, query: Map<String, String> = emptyMap(), body: JsonObject? = null): ByteArray {
        if (method != "GET" && isOffline()) throw JellyfinError.Offline
        val request = Request.Builder()
            .url(url(path, query))
            .method(method, requestBody(method, body))
            .header("Authorization", clientInfo.header(session.accessToken))
            .header("User-Agent", clientInfo.userAgent)
            .build()
        return execute(request, http, log)
    }

    private fun url(path: String, query: Map<String, String>): HttpUrl = buildUrl(host, path, query)

    companion object {
        internal const val SONG_FIELDS = "Genres,MediaSources,ParentId"
        private const val ALBUM_FIELDS = "Genres,DateCreated,ChildCount"
        private const val DEFAULT_PORT = 8096

        /** What Android's player plays without a transcode, in Jellyfin's `container|codec` notation. */
        val PLAYABLE_CONTAINERS = listOf("mp3", "aac", "m4a|aac", "m4b|aac", "flac", "ogg|vorbis", "ogg|opus", "webma|opus", "wav")

        private val jsonType = "application/json".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /**
         * Accepts "host", "host:port" or a full URL. A bare host gets http and, without a port,
         * Jellyfin's default 8096, which is how most home servers are reached; an explicit
         * https:// is kept as given.
         */
        fun normalizeHost(rawInput: String): HttpUrl? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
            val text = if (hasScheme) trimmed else "http://$trimmed"
            val url = text.toHttpUrlOrNull()?.takeIf { it.host.isNotEmpty() } ?: return null
            val builder = url.newBuilder()
            if (!hasScheme && !hasExplicitPort(text)) builder.port(DEFAULT_PORT)
            val segments = url.pathSegments
            if (segments.size > 1 && segments.last().isEmpty()) builder.removePathSegment(segments.size - 1)
            return builder.build()
        }

        /**
         * The addresses to try for what was typed. With a scheme, just that. Without one, https
         * first (a server behind a proxy or on a public name), then http on Jellyfin's port 8096
         * (a server at home), then plain http.
         */
        fun candidateHosts(rawInput: String): List<HttpUrl> {
            val trimmed = rawInput.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return listOfNotNull(normalizeHost(trimmed))
            val home = normalizeHost(trimmed) ?: return emptyList()
            return buildList {
                normalizeHost("https://$trimmed")?.let { add(it) }
                add(home)
                normalizeHost("http://$trimmed")?.takeIf { it != home }?.let { add(it) }
            }
        }

        private fun hasExplicitPort(url: String): Boolean {
            val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            val afterHost = if (authority.startsWith("[")) authority.substringAfter(']', "") else authority
            return afterHost.contains(':')
        }

        // Before signing in

        /** Name, version and local address, without signing in; also tells whether there is a Jellyfin there at all. */
        suspend fun serverInfo(host: HttpUrl, clientInfo: ClientInfo, http: OkHttpClient, log: NetworkLog = NetworkLog.None): ServerInfo {
            val request = Request.Builder().url(buildUrl(host, "System/Info/Public")).header("User-Agent", clientInfo.userAgent).build()
            return decode(execute(request, http, log), ServerInfo.serializer(), log)
        }

        suspend fun signIn(
            host: HttpUrl,
            username: String,
            password: String,
            clientInfo: ClientInfo,
            http: OkHttpClient,
            log: NetworkLog = NetworkLog.None,
        ): Session {
            val body = buildJsonObject {
                put("Username", username)
                put("Pw", password)
            }
            val request = unsignedRequest(host, "Users/AuthenticateByName", clientInfo, body)
            return decode(execute(request, http, log), AuthResponse.serializer(), log).session
        }

        suspend fun isQuickConnectEnabled(host: HttpUrl, clientInfo: ClientInfo, http: OkHttpClient): Boolean {
            val request = Request.Builder().url(buildUrl(host, "QuickConnect/Enabled")).header("User-Agent", clientInfo.userAgent).build()
            return try {
                json.decodeFromString(Boolean.serializer(), execute(request, http, NetworkLog.None).decodeToString())
            } catch (e: JellyfinError) {
                false
            } catch (e: SerializationException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        suspend fun startQuickConnect(host: HttpUrl, clientInfo: ClientInfo, http: OkHttpClient, log: NetworkLog = NetworkLog.None): QuickConnect {
            val request = unsignedRequest(host, "QuickConnect/Initiate", clientInfo, body = null)
            return decode(execute(request, http, log), QuickConnectDto.serializer(), log).let { QuickConnect(it.secret, it.code, it.authenticated) }
        }

        /** Polled while the code is on screen; [QuickConnect.isApproved] turns true once someone approves it. */
        suspend fun checkQuickConnect(
            host: HttpUrl,
            secret: String,
            clientInfo: ClientInfo,
            http: OkHttpClient,
            log: NetworkLog = NetworkLog.None,
        ): QuickConnect {
            val request = Request.Builder()
                .url(buildUrl(host, "QuickConnect/Connect", mapOf("secret" to secret)))
                .header("User-Agent", clientInfo.userAgent)
                .build()
            return decode(execute(request, http, log), QuickConnectDto.serializer(), log).let { QuickConnect(it.secret, it.code, it.authenticated) }
        }

        suspend fun finishQuickConnect(
            host: HttpUrl,
            secret: String,
            clientInfo: ClientInfo,
            http: OkHttpClient,
            log: NetworkLog = NetworkLog.None,
        ): Session {
            val request = unsignedRequest(host, "Users/AuthenticateWithQuickConnect", clientInfo, buildJsonObject { put("Secret", secret) })
            return decode(execute(request, http, log), AuthResponse.serializer(), log).session
        }

        /** A POST that introduces the app without a token yet. */
        private fun unsignedRequest(host: HttpUrl, path: String, clientInfo: ClientInfo, body: JsonObject?): Request =
            Request.Builder()
                .url(buildUrl(host, path))
                .post(requestBody("POST", body)!!)
                .header("Authorization", clientInfo.header(token = null))
                .header("User-Agent", clientInfo.userAgent)
                .build()

        /** Sorted, so the same question always makes the same URL. */
        internal fun buildUrl(host: HttpUrl, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
            val builder = host.newBuilder().addPathSegments(path)
            query.entries.sortedBy { it.key }.forEach { builder.addQueryParameter(it.key, it.value) }
            return builder.build()
        }

        /** POST needs a body even when there is nothing to say; GET and DELETE send none. */
        private fun requestBody(method: String, body: JsonObject?): RequestBody? = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        internal suspend fun execute(request: Request, http: OkHttpClient, log: NetworkLog): ByteArray {
            val answer = try {
                http.newCall(request).awaitAnswer()
            } catch (e: IOException) {
                log.error("${request.method} ${request.url.encodedPath} failed: ${e.javaClass.simpleName}")
                throw JellyfinError.Network
            }
            if (answer.code !in 200..299) log.error("${request.method} ${request.url.encodedPath}: HTTP ${answer.code}")
            return when (answer.code) {
                in 200..299 -> answer.body
                401 -> throw JellyfinError.Unauthorized
                403 -> throw JellyfinError.Forbidden
                404 -> throw JellyfinError.NotFound
                else -> throw JellyfinError.Http(answer.code)
            }
        }

        internal fun <T> decode(data: ByteArray, serializer: KSerializer<T>, log: NetworkLog = NetworkLog.None): T = try {
            json.decodeFromString(serializer, data.decodeToString())
        } catch (e: SerializationException) {
            log.error("unreadable answer as ${serializer.descriptor.serialName}: ${e.whereItFailed()}")
            throw JellyfinError.Decoding
        } catch (e: IllegalArgumentException) {
            log.error("unreadable answer as ${serializer.descriptor.serialName}")
            throw JellyfinError.Decoding
        }
    }

    private fun <T> decode(data: ByteArray, serializer: KSerializer<T>): T = Companion.decode(data, serializer, log)
}
