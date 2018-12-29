/* Created by Adam Ratzman (2018) */
package com.adamratzman.spotify.main

import com.adamratzman.spotify.endpoints.client.ClientFollowingAPI
import com.adamratzman.spotify.endpoints.client.ClientLibraryAPI
import com.adamratzman.spotify.endpoints.client.ClientPersonalizationAPI
import com.adamratzman.spotify.endpoints.client.ClientPlayerAPI
import com.adamratzman.spotify.endpoints.client.ClientPlaylistAPI
import com.adamratzman.spotify.endpoints.client.ClientUserAPI
import com.adamratzman.spotify.endpoints.public.AlbumAPI
import com.adamratzman.spotify.endpoints.public.ArtistsAPI
import com.adamratzman.spotify.endpoints.public.BrowseAPI
import com.adamratzman.spotify.endpoints.public.FollowingAPI
import com.adamratzman.spotify.endpoints.public.PlaylistsAPI
import com.adamratzman.spotify.endpoints.public.SearchAPI
import com.adamratzman.spotify.endpoints.public.TracksAPI
import com.adamratzman.spotify.endpoints.public.UserAPI
import com.adamratzman.spotify.utils.Token
import com.adamratzman.spotify.utils.byteEncode
import com.adamratzman.spotify.utils.toObject
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.jsoup.Jsoup
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal val base = "https://api.spotify.com/v1"

// Kotlin DSL builder
fun spotifyApi(block: SpotifyApiBuilder.() -> Unit) = SpotifyApiBuilder().apply(block)

// Java-friendly builder
class SpotifyApiBuilderJava(val clientId: String, val clientSecret: String) {
    var redirectUri: String? = null
    var authorizationCode: String? = null
    var tokenString: String? = null
    var token: Token? = null

    fun redirectUri(redirectUri: String?): SpotifyApiBuilderJava {
        this.redirectUri = redirectUri
        return this
    }

    fun authorizationCode(authorizationCode: String?): SpotifyApiBuilderJava {
        this.authorizationCode = authorizationCode
        return this
    }

    fun tokenString(tokenString: String?): SpotifyApiBuilderJava {
        this.tokenString = tokenString
        return this
    }

    fun token(token: Token?): SpotifyApiBuilderJava {
        this.token = token
        return this
    }

    fun buildCredentialed() = spotifyApi {
        credentials {
            clientId = this@SpotifyApiBuilderJava.clientId
            clientSecret = this@SpotifyApiBuilderJava.clientSecret
        }
        authentication {
            token = this@SpotifyApiBuilderJava.token
            tokenString = this@SpotifyApiBuilderJava.tokenString
        }
    }.buildCredentialed()

    fun buildClient(automaticRefresh: Boolean = false) = spotifyApi {
        credentials {
            clientId = this@SpotifyApiBuilderJava.clientId
            clientSecret = this@SpotifyApiBuilderJava.clientSecret
            redirectUri = this@SpotifyApiBuilderJava.redirectUri
        }
        authentication {
            authorizationCode = this@SpotifyApiBuilderJava.authorizationCode
            tokenString = this@SpotifyApiBuilderJava.tokenString
            token = this@SpotifyApiBuilderJava.token
        }
    }.buildClient(automaticRefresh)
}

/**
 * @property clientId the client id of your Spotify application
 * @property clientSecret the client secret of your Spotify application
 * @property redirectUri nullable redirect uri (use if you're doing client authentication
 */
class SpotifyCredentialsBuilder {
    var clientId: String? = null
    var clientSecret: String? = null
    var redirectUri: String? = null

    fun build() =
        if (clientId?.isNotEmpty() == false || clientSecret?.isNotEmpty() == false) throw IllegalArgumentException("clientId or clientSecret is empty")
        else SpotifyCredentials(clientId, clientSecret, redirectUri)
}

data class SpotifyCredentials(val clientId: String?, val clientSecret: String?, val redirectUri: String?)

/**
 * Authentication methods.
 *
 * @property authorizationCode Only available when building [SpotifyClientAPI]. Spotify auth code
 * @property token Build the API using an existing token. If you're building [SpotifyClientAPI], this
 * will be your **access** token. If you're building [SpotifyAPI], it will be your **refresh** token
 * @property tokenString Build the API using an existing token (string). If you're building [SpotifyClientAPI], this
 * will be your **access** token. If you're building [SpotifyAPI], it will be your **refresh** token. There is a *very*
 * limited time constraint on these before the API automatically refreshes them
 */
class SpotifyUserAuthorizationBuilder(
    var authorizationCode: String? = null,
    var tokenString: String? = null,
    var token: Token? = null
)

class SpotifyApiBuilder {
    private var credentials: SpotifyCredentials = SpotifyCredentials(null, null, null)
    private var authentication = SpotifyUserAuthorizationBuilder()

    fun credentials(block: SpotifyCredentialsBuilder.() -> Unit) {
        credentials = SpotifyCredentialsBuilder().apply(block).build()
    }

    /**
     * Allows you to authenticate a [SpotifyClientAPI] with an authorization code
     * or build [SpotifyAPI] using a refresh token
     */
    fun authentication(block: SpotifyUserAuthorizationBuilder.() -> Unit) {
        authentication = SpotifyUserAuthorizationBuilder().apply(block)
    }

    fun getAuthorizationUrl(vararg scopes: SpotifyScope): String {
        if (credentials.redirectUri == null || credentials.clientId == null) {
            throw IllegalArgumentException("You didn't specify a redirect uri or client id in the credentials block!")
        }
        return getAuthUrlFull(*scopes, clientId = credentials.clientId!!, redirectUri = credentials.redirectUri!!)
    }

    fun buildCredentialed(): SpotifyAPI {
        val clientId = credentials.clientId
        val clientSecret = credentials.clientSecret
        if ((clientId == null || clientSecret == null) && (authentication.token == null && authentication.tokenString == null)) {
            throw IllegalArgumentException("You didn't specify a client id or client secret in the credentials block!")
        }
        return when {
            authentication.token != null -> {
                SpotifyAppAPI(clientId ?: "not-set", clientSecret ?: "not-set", authentication.token!!)
            }
            authentication.tokenString != null -> {
                SpotifyAppAPI(
                    clientId ?: "not-set",
                    clientSecret ?: "not-set",
                    Token(
                        authentication.tokenString!!, "client_credentials",
                        60000, null, null
                    )
                )
            }
            else -> try {
                val token = Gson().fromJson(
                    Jsoup.connect("https://accounts.spotify.com/api/token")
                        .data("grant_type", "client_credentials")
                        .header("Authorization", "Basic " + ("$clientId:$clientSecret".byteEncode()))
                        .ignoreContentType(true).post().body().text(), Token::class.java
                ) ?: throw IllegalArgumentException("Invalid credentials provided")
                SpotifyAppAPI(
                    clientId ?: throw IllegalArgumentException(),
                    clientSecret ?: throw IllegalArgumentException(),
                    token
                )
            } catch (e: Exception) {
                throw SpotifyException("Invalid credentials provided in the login process", e)
            }
        }
    }

    fun buildClient(automaticRefresh: Boolean = false): SpotifyClientAPI =
        buildClient(
            authentication.authorizationCode, authentication.tokenString,
            authentication.token, automaticRefresh
        )

    /**
     * Build the client api by providing an authorization code, token string, or token object. Only one of the following
     * needs to be provided
     *
     * @param authorizationCode Spotify authorization code retrieved after authentication
     * @param tokenString Spotify authorization token
     * @param token [Token] object (useful if you already have exchanged an authorization code yourself
     * @param automaticRefresh automatically refresh the token. otherwise, the authorization will eventually expire. **only** valid when
     * [authorizationCode] or [token] is provided
     */
    private fun buildClient(
        authorizationCode: String? = null,
        tokenString: String? = null,
        token: Token? = null,
        automaticRefresh: Boolean = false
    ): SpotifyClientAPI {
        val clientId = credentials.clientId
        val clientSecret = credentials.clientSecret
        val redirectUri = credentials.redirectUri

        if ((clientId == null || clientSecret == null || redirectUri == null) && (token == null && tokenString == null)) {
            throw IllegalArgumentException("You need to specify a valid clientId, clientSecret, and redirectUri in the credentials block!")
        }
        return when {
            authorizationCode != null -> try {
                SpotifyClientAPI(
                    clientId ?: throw IllegalArgumentException(),
                    clientSecret ?: throw IllegalArgumentException(),
                    Jsoup.connect("https://accounts.spotify.com/api/token")
                        .data("grant_type", "authorization_code")
                        .data("code", authorizationCode)
                        .data("redirect_uri", redirectUri)
                        .header("Authorization", "Basic " + ("$clientId:$clientSecret").byteEncode())
                        .ignoreContentType(true).post().body().text().toObject(Gson(), Token::class.java),
                    automaticRefresh,
                    redirectUri ?: throw IllegalArgumentException()
                )
            } catch (e: Exception) {
                throw SpotifyException("Invalid credentials provided in the login process", e)
            }
            token != null -> SpotifyClientAPI(
                clientId ?: "not-set",
                clientSecret ?: "not-set",
                token,
                automaticRefresh,
                redirectUri ?: "not-set"
            )
            tokenString != null -> SpotifyClientAPI(
                clientId ?: "not-set", clientSecret ?: "not-set", Token(
                    tokenString, "client_credentials", 1000,
                    null, null
                ), false, redirectUri ?: "not-set"
            )
            else -> throw IllegalArgumentException(
                "At least one of: authorizationCode, tokenString, or token must be provided " +
                    "to build a SpotifyClientAPI object"
            )
        }
    }
}

abstract class SpotifyAPI internal constructor(val clientId: String, val clientSecret: String, var token: Token) {
    internal var expireTime = System.currentTimeMillis() + token.expires_in * 1000
    internal val executor = Executors.newScheduledThreadPool(1)
    val gson = GsonBuilder().setLenient().create()!!

    abstract val search: SearchAPI
    abstract val albums: AlbumAPI
    abstract val browse: BrowseAPI
    abstract val artists: ArtistsAPI
    abstract val playlists: PlaylistsAPI
    abstract val users: UserAPI
    abstract val tracks: TracksAPI
    abstract val following: FollowingAPI

    internal val logger = SpotifyLogger(true)

    abstract fun refreshToken()

    fun useLogger(enable: Boolean) {
        logger.enabled = enable
    }

    fun getAuthorizationUrl(vararg scopes: SpotifyScope, redirectUri: String): String {
        return getAuthUrlFull(*scopes, clientId = clientId, redirectUri = redirectUri)
    }
}

class SpotifyAppAPI internal constructor(clientId: String, clientSecret: String, token: Token) :
    SpotifyAPI(clientId, clientSecret, token) {
    override val search: SearchAPI = SearchAPI(this)
    override val albums: AlbumAPI = AlbumAPI(this)
    override val browse: BrowseAPI = BrowseAPI(this)
    override val artists: ArtistsAPI = ArtistsAPI(this)
    override val playlists: PlaylistsAPI = PlaylistsAPI(this)
    override val users: UserAPI = UserAPI(this)
    override val tracks: TracksAPI = TracksAPI(this)
    override val following: FollowingAPI = FollowingAPI(this)

    init {
        if (clientId == "not-set" || clientSecret == "not-set") {
            logger.logWarning("Token refresh is disabled - application parameters not set")
        }
    }

    override fun refreshToken() {
        if (clientId != "not-set" && clientSecret != "not-set")
            token = gson.fromJson(
                Jsoup.connect("https://accounts.spotify.com/api/token")
                    .data("grant_type", "client_credentials")
                    .header("Authorization", "Basic " + ("$clientId:$clientSecret".byteEncode()))
                    .ignoreContentType(true).post().body().text(), Token::class.java
            )
    }
}

class SpotifyClientAPI internal constructor(
    clientId: String,
    clientSecret: String,
    token: Token,
    automaticRefresh: Boolean = false,
    var redirectUri: String
) : SpotifyAPI(clientId, clientSecret, token) {
    override val search: SearchAPI = SearchAPI(this)
    override val albums: AlbumAPI = AlbumAPI(this)
    override val browse: BrowseAPI = BrowseAPI(this)
    override val artists: ArtistsAPI = ArtistsAPI(this)
    override val playlists: ClientPlaylistAPI = ClientPlaylistAPI(this)
    override val users: ClientUserAPI = ClientUserAPI(this)
    override val tracks: TracksAPI = TracksAPI(this)
    override val following: ClientFollowingAPI = ClientFollowingAPI(this)
    val personalization: ClientPersonalizationAPI = ClientPersonalizationAPI(this)
    val library: ClientLibraryAPI = ClientLibraryAPI(this)
    val player: ClientPlayerAPI = ClientPlayerAPI(this)

    val userId: String

    init {
        init(automaticRefresh)
        userId = users.getUserProfile().complete().id
    }

    private fun init(automaticRefresh: Boolean) {
        if (automaticRefresh) {
            if (clientId != "not-set" && clientSecret != "not-set" && redirectUri != "not-set") {
                browse.getAvailableGenreSeeds().complete()

                executor.scheduleAtFixedRate(
                    { refreshToken() },
                    ((token.expires_in - 30).toLong()),
                    (token.expires_in - 30).toLong(),
                    TimeUnit.SECONDS
                )
                if (token.expires_in > 60) {
                    executor.scheduleAtFixedRate(
                        { refreshToken() },
                        (token.expires_in - 30).toLong(),
                        (token.expires_in - 30).toLong(),
                        TimeUnit.SECONDS
                    )
                } else {
                    refreshToken()
                    init(automaticRefresh)
                }
            } else logger.logWarning("Automatic refresh unavailable - client parameters not set")
        }
    }

    fun cancelRefresh() = executor.shutdown()

    override fun refreshToken() {
        val tempToken = gson.fromJson(
            Jsoup.connect("https://accounts.spotify.com/api/token")
                .data("grant_type", "client_credentials")
                .data("refresh_token", token.refresh_token ?: "")
                .header("Authorization", "Basic " + ("$clientId:$clientSecret").byteEncode())
                .ignoreContentType(true).post().body().text(), Token::class.java
        )
        if (tempToken == null) {
            logger.logWarning("Spotify token refresh failed")
        } else {
            this.token = tempToken
            logger.logInfo("Successfully refreshed the Spotify token")
        }
    }

    fun getAuthorizationUrl(vararg scopes: SpotifyScope): String {
        return getAuthUrlFull(*scopes, clientId = clientId, redirectUri = redirectUri)
    }
}

private fun getAuthUrlFull(vararg scopes: SpotifyScope, clientId: String, redirectUri: String): String {
    return "https://accounts.spotify.com/authorize/?client_id=$clientId" +
        "&response_type=code" +
        "&redirect_uri=$redirectUri" +
        if (scopes.isEmpty()) "" else "&scope=${scopes.joinToString("%20") { it.uri }}"
}