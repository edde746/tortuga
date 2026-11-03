package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.AppleClient
import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.trackers.devTrackerUrl
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the server reads from its environment ([fromEnvironment]); `server/README.md` documents each variable.
 * Secrets may come from a file instead: `NAME_FILE=/run/secrets/…` (Docker secrets). A setting the server can't use
 * fails [fromEnvironment] with an [IllegalArgumentException] that names it, which `main` logs as its one line.
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val database: DatabaseConfig,
    /** Seals records at rest; loaded from `TORTUGA_DATA_KEY_FILE`, without which the server doesn't start. */
    val dataKey: DataKey,
    /**
     * Behind a reverse proxy: take the client address from `X-Forwarded-For`'s last entry, the address the proxy in front
     * saw (the per-client limits count it, [com.edde746.tortuga.server.http.clientAddress]). `Forwarded` isn't read.
     */
    val trustForwardedHeaders: Boolean = false,
    val apple: AppleConfig?,
    val google: ProviderConfig?,
    val oidc: ProviderConfig?,
    val github: GitHubConfig? = null,
    val accountQuotaBytes: Long = 64L * 1024 * 1024,
    /**
     * The heap HTTP calls may hold at once ([ByteBudget]): a quarter of the heap, which must hold the largest push (the
     * server doesn't start otherwise, [ByteBudget.ofHeap]).
     */
    val requestBytes: Long = ByteBudget.ofHeap(),
    val accessTokenTtl: Duration = 1.hours,
    /** A session not refreshed for this long ends. */
    val sessionIdleTtl: Duration = 180.days,
    /** How often expired nonces, links, handoffs and sessions are deleted. */
    val janitorInterval: Duration = 1.minutes,
    val rateLimits: RateLimits = RateLimits(),
    val trackerOAuth: TrackerOAuthConfig = TrackerOAuthConfig(publicUrl = null, myAnimeList = null, aniList = null),
    /**
     * Account change notice streams (`GET /v2/sync/events`) open at once; past it a new one gets 503 and the app keeps
     * to its periodic pulls (server/README.md, Memory and load). Zero: none.
     */
    val maxEventStreams: Int = 3000,
    /**
     * A directory on the file system that holds the database (`TORTUGA_DATABASE_DISK_PATH`; compose mounts Postgres's
     * volume there): while it has less than [minFreeDatabaseDiskBytes] free, pushes are refused. Null: not watched.
     */
    val databaseDisk: Path? = null,
    /**
     * Free space the database's disk keeps (`TORTUGA_DATABASE_MIN_FREE_MB`): 2 GiB, the room Postgres's WAL may take
     * past a checkpoint (`max_wal_size`, 1 GB by default, as on the hosted instance) and as much again for the host's
     * logs and a deploy's image.
     */
    val minFreeDatabaseDiskBytes: Long = 2048L shl 20,
    /**
     * Development stacks only (`TORTUGA_DEV_TRACKERS_URL`): the fake tracker server (`tools/media/test_trackers.py`)
     * that tracker refreshes and revokes go to instead of the services. Null, the default, reaches the services.
     */
    val devTrackersUrl: String? = null,
) {
    companion object {
        const val DEFAULT_APPLE_ISSUER: String = "https://appleid.apple.com"
        const val DEFAULT_GOOGLE_ISSUER: String = "https://accounts.google.com"
        const val DEFAULT_GITHUB_URL: String = "https://github.com"
        const val DEFAULT_GITHUB_API_URL: String = "https://api.github.com"

        /** The official apps' Sign in with Apple audience: the bundle ID shared by the iOS, macOS and tvOS apps. */
        const val DEFAULT_APPLE_CLIENT_IDS: String = "com.edde746.tortuga"

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = EnvReader(env).run {
            val dataKeyFile = string(DataKey.ENV) ?: throw IllegalArgumentException(
                "${DataKey.ENV} is not set: point it at the server's data key, a file of ${DataKey.SIZE} random " +
                    "bytes kept outside the database (see server/README.md)",
            )
            val dataKey = DataKey.load(Path.of(dataKeyFile))
            val appleEnabled = boolean("TORTUGA_APPLE_ENABLED", true)
            val appleKey = listOf("TORTUGA_APPLE_TEAM_ID", "TORTUGA_APPLE_KEY_ID", "TORTUGA_APPLE_PRIVATE_KEY")
                .map { secret(it) }
            val googleClientIds = list("TORTUGA_GOOGLE_CLIENT_IDS")
            val oidcIssuer = string("TORTUGA_OIDC_ISSUER")
            ServerConfig(
                host = string("TORTUGA_HOST") ?: "0.0.0.0",
                port = int("TORTUGA_PORT", 8080),
                database = DatabaseConfig(
                    url = required("TORTUGA_DATABASE_URL"),
                    user = required("TORTUGA_DATABASE_USER"),
                    password = secret("TORTUGA_DATABASE_PASSWORD") ?: "",
                    poolSize = positiveInt("TORTUGA_DATABASE_POOL_SIZE", 10),
                ),
                dataKey = dataKey,
                trustForwardedHeaders = boolean("TORTUGA_TRUST_FORWARDED_HEADERS", false),
                apple = if (!appleEnabled) null else AppleConfig(
                    issuer = DEFAULT_APPLE_ISSUER,
                    clientIds = list("TORTUGA_APPLE_CLIENT_IDS").ifEmpty { DEFAULT_APPLE_CLIENT_IDS.split(',') },
                    servicesId = string("TORTUGA_APPLE_SERVICES_ID"),
                    signingKey = when {
                        appleKey.all { it != null } -> AppleSigningKey(appleKey[0]!!, appleKey[1]!!, appleKey[2]!!).also {
                            checkAppleKey(it.privateKeyPem)
                        }
                        appleKey.all { it == null } -> null
                        else -> throw IllegalArgumentException(
                            "Set all of TORTUGA_APPLE_TEAM_ID, TORTUGA_APPLE_KEY_ID and TORTUGA_APPLE_PRIVATE_KEY, or none",
                        )
                    },
                    webReturn = boolean("TORTUGA_APPLE_WEB_RETURN", false),
                    webRedirectUri = string("TORTUGA_PUBLIC_URL")?.trimEnd('/')?.plus(SyncPaths.APPLE_RETURN),
                ).also {
                    require(it.signingKey == null || it.servicesId == null || it.webRedirectUri != null) {
                        "Exchanging web-flow codes (TORTUGA_APPLE_SERVICES_ID with a signing key) needs TORTUGA_PUBLIC_URL"
                    }
                },
                google = googleClientIds.takeIf { it.isNotEmpty() }?.let {
                    ProviderConfig(issuer = DEFAULT_GOOGLE_ISSUER, clientIds = it)
                },
                oidc = oidcIssuer?.let {
                    ProviderConfig(
                        // As written: tokens and the discovery document must carry it exactly (Authentik's ends in /).
                        issuer = it,
                        clientIds = list("TORTUGA_OIDC_CLIENT_IDS").also { ids ->
                            require(ids.isNotEmpty()) { "TORTUGA_OIDC_ISSUER needs TORTUGA_OIDC_CLIENT_IDS" }
                        },
                        displayName = string("TORTUGA_OIDC_NAME"),
                        jwksUri = string("TORTUGA_OIDC_JWKS_URL"),
                    )
                },
                github = github(),
                accountQuotaBytes = long("TORTUGA_ACCOUNT_QUOTA_BYTES", 64L * 1024 * 1024),
                accessTokenTtl = positiveInt("TORTUGA_ACCESS_TOKEN_TTL_SECONDS", 3600).seconds,
                sessionIdleTtl = positiveInt("TORTUGA_SESSION_IDLE_DAYS", 180).days,
                trackerOAuth = trackerOAuth(),
                maxEventStreams = int("TORTUGA_MAX_EVENT_STREAMS", 3000).also {
                    require(it >= 0) { "TORTUGA_MAX_EVENT_STREAMS must be 0 or more" }
                },
                databaseDisk = string("TORTUGA_DATABASE_DISK_PATH")?.let { Path.of(it) }?.also {
                    require(Files.isDirectory(it)) { "TORTUGA_DATABASE_DISK_PATH: $it is not a directory" }
                },
                minFreeDatabaseDiskBytes = long("TORTUGA_DATABASE_MIN_FREE_MB", 2048).also {
                    require(it >= 0) { "TORTUGA_DATABASE_MIN_FREE_MB must be 0 or more" }
                } shl 20,
                devTrackersUrl = fakeTrackersUrl("TORTUGA_DEV_TRACKERS_URL"),
            )
        }
    }
}

/** Fails, naming the variable, unless [pem] is a Sign in with Apple key as AppleClient reads it. */
private fun checkAppleKey(pem: String) {
    try {
        AppleClient.parsePrivateKey(pem)
    } catch (e: Exception) {
        throw IllegalArgumentException("TORTUGA_APPLE_PRIVATE_KEY is not a Sign in with Apple key, a .p8 file's PKCS#8 PEM ($e)")
    }
}

/**
 * The relay's settings; a service is configured when its client id is set. On a development stack the fake tracker
 * server (`tools/media/test_trackers.py`) stands in for both services' sign-in: `TORTUGA_DEV_TRACKER_OAUTH_URL`
 * as the server reaches it (code exchanges), `TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL` as browsers reach it
 * (authorization pages); both or neither.
 */
private fun EnvReader.trackerOAuth(): TrackerOAuthConfig {
    val publicUrl = string("TORTUGA_PUBLIC_URL")?.trimEnd('/')
    val malId = string("TORTUGA_MAL_CLIENT_ID")
    val aniListId = string("TORTUGA_ANILIST_CLIENT_ID")
    val aniListSecret = secret("TORTUGA_ANILIST_CLIENT_SECRET")
    val devUrl = fakeTrackersUrl("TORTUGA_DEV_TRACKER_OAUTH_URL")
    val devBrowserUrl = fakeTrackersUrl("TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL")
    require((devUrl == null) == (devBrowserUrl == null)) {
        "Set both TORTUGA_DEV_TRACKER_OAUTH_URL and TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL, or neither"
    }
    fun client(clientId: String, clientSecret: String?, authorizeUrl: String, tokenUrl: String) = OAuthClientConfig(
        clientId,
        clientSecret,
        authorizeUrl = devBrowserUrl?.let { devTrackerUrl(it, authorizeUrl) } ?: authorizeUrl,
        tokenUrl = devUrl?.let { devTrackerUrl(it, tokenUrl) } ?: tokenUrl,
    )
    require((malId == null && aniListId == null) || publicUrl != null) {
        "The tracker OAuth relay (TORTUGA_MAL_CLIENT_ID, TORTUGA_ANILIST_CLIENT_ID) needs TORTUGA_PUBLIC_URL"
    }
    require((aniListId == null) == (aniListSecret == null)) {
        "Set both TORTUGA_ANILIST_CLIENT_ID and TORTUGA_ANILIST_CLIENT_SECRET, or neither"
    }
    return TrackerOAuthConfig(
        publicUrl = publicUrl,
        myAnimeList = malId?.let {
            client(it, secret("TORTUGA_MAL_CLIENT_SECRET"), TrackerOAuthConfig.MAL_AUTHORIZE_URL, TrackerOAuthConfig.MAL_TOKEN_URL)
        },
        aniList = aniListId?.let {
            client(it, aniListSecret, TrackerOAuthConfig.ANILIST_AUTHORIZE_URL, TrackerOAuthConfig.ANILIST_TOKEN_URL)
        },
    )
}

/** Development stacks only: [name], the fake tracker server's http(s) URL, if set. */
private fun EnvReader.fakeTrackersUrl(name: String): String? = string(name)?.also {
    val url = runCatching { URI(it) }.getOrNull()
    require(url != null && (url.scheme == "http" || url.scheme == "https") && !url.host.isNullOrEmpty()) {
        "$name must be the fake tracker server's http(s) URL"
    }
}

/** Sign in with GitHub: on when both the client ID and the secret are set. */
private fun EnvReader.github(): GitHubConfig? {
    val clientId = string("TORTUGA_GITHUB_CLIENT_ID")
    val clientSecret = secret("TORTUGA_GITHUB_CLIENT_SECRET")
    require((clientId == null) == (clientSecret == null)) {
        "Set both TORTUGA_GITHUB_CLIENT_ID and TORTUGA_GITHUB_CLIENT_SECRET, or neither"
    }
    if (clientId == null || clientSecret == null) return null
    val url = (string("TORTUGA_GITHUB_URL") ?: ServerConfig.DEFAULT_GITHUB_URL).trimEnd('/')
    return GitHubConfig(
        clientId = clientId,
        clientSecret = clientSecret,
        url = url,
        apiUrl = (string("TORTUGA_GITHUB_API_URL") ?: ServerConfig.DEFAULT_GITHUB_API_URL).trimEnd('/'),
        tokenUrl = string("TORTUGA_GITHUB_TOKEN_URL") ?: "$url/login/oauth/access_token",
    )
}

/**
 * Requests per minute. Keyed by client address, except [linkAnswer] (claim, approve and reject), which is keyed by
 * the session's access token so a signed-in device can't enumerate codes.
 */
data class RateLimits(
    /**
     * Every request that carries a token (sign-in, account, devices, links, sync, tracker connections), on top of its
     * route's own limit; generous, so the devices of a household behind one address stay under it.
     */
    val api: Int = 300,
    /** Nonce, sign-in, refresh, and the Apple and GitHub web flows' starts and pickups. */
    val auth: Int = 30,
    val linkStart: Int = 10,
    val linkAnswer: Int = 20,
    /** A waiting device polls every two seconds or so; this leaves room for a few behind one address. */
    val linkPoll: Int = 120,
    /** Tracker sign-ins started (the OAuth relay's [com.edde746.tortuga.trackers.relay.OAuthRelayPaths.START]). */
    val trackerOAuthStart: Int = 10,
    /**
     * Tracker sign-in result polls. Each is held up to 50 seconds, so a waiting device makes one or two a minute; this
     * leaves room for several behind one address.
     */
    val trackerOAuthResult: Int = 30,
    /** The Apple and GitHub web flows' return pages, which a browser reaches once per sign-in. */
    val signInReturn: Int = 20,
)

/**
 * The tracker OAuth relay (protocol in `shared/…/trackers/relay/OAuthRelayApi.kt`). Browsers reach it at
 * [publicUrl] (`TORTUGA_PUBLIC_URL`), and each service's registered redirect URI is
 * `publicUrl + OAuthRelayPaths.callback(service)`. A service without a client registration answers "not configured".
 *
 * @property myAnimeList MyAnimeList's client (authorization code with PKCE, `plain` challenges: MyAnimeList rejects
 *   `S256`). Its secret is optional: MyAnimeList issues none to non-web app types.
 * @property aniList AniList's client (authorization code; it needs the secret).
 * @property sessionTtl how long a sign-in waits for the browser and for the app to collect the result.
 * @property resultWait how long a result poll is held before answering 204.
 * @property maxSessions sign-ins in progress at once, across all clients; beyond it starts answer 503. Only a memory
 *   backstop (a session holds a few short strings): [maxSessionsPerAddress] is what keeps one client to a few.
 * @property maxSessionsPerAddress sign-ins in progress at once per client address
 *   ([com.edde746.tortuga.server.http.clientAddress]); beyond it that address's starts answer 429. Three covers a
 *   person's phone and TV and a retry.
 */
data class TrackerOAuthConfig(
    val publicUrl: String?,
    val myAnimeList: OAuthClientConfig?,
    val aniList: OAuthClientConfig?,
    val sessionTtl: Duration = 10.minutes,
    val resultWait: Duration = 50.seconds,
    val maxSessions: Int = 20_000,
    val maxSessionsPerAddress: Int = 3,
) {
    companion object {
        const val MAL_AUTHORIZE_URL: String = "https://myanimelist.net/v1/oauth2/authorize"
        const val MAL_TOKEN_URL: String = "https://myanimelist.net/v1/oauth2/token"
        const val ANILIST_AUTHORIZE_URL: String = "https://anilist.co/api/v2/oauth/authorize"
        const val ANILIST_TOKEN_URL: String = "https://anilist.co/api/v2/oauth/token"
    }
}

/**
 * A client registration with a tracker service. [authorizeUrl] and [tokenUrl] are the service's; only tests and a
 * development stack's fake tracker server (`TORTUGA_DEV_TRACKER_OAUTH_URL`) change them.
 */
class OAuthClientConfig(val clientId: String, val clientSecret: String?, val authorizeUrl: String, val tokenUrl: String) {
    override fun toString(): String = "OAuthClientConfig(clientId=$clientId, authorizeUrl=$authorizeUrl, tokenUrl=$tokenUrl)"
}

data class DatabaseConfig(val url: String, val user: String, val password: String, val poolSize: Int) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, poolSize=$poolSize)"
}

/**
 * An OpenID Connect provider whose ID tokens sign in: Google, or the self-hoster's own ([displayName] names it).
 * [jwksUri] fetches the signing keys from there instead of the discovery document's `jwks_uri` (the provider's public
 * [issuer] is still what tokens must carry).
 */
data class ProviderConfig(val issuer: String, val clientIds: List<String>, val displayName: String? = null, val jwksUri: String? = null)

/**
 * Sign in with Apple.
 *
 * @property issuer Apple's issuer; its `/auth/token` and `/auth/revoke` are the endpoints used. Only tests change it.
 * @property clientIds accepted audiences of native sign-ins: the apps' bundle ID.
 * @property servicesId accepted audience of the web flow (Android, Windows, Linux): the Services ID.
 * @property signingKey the Sign in with Apple key: with it the server exchanges authorization codes and revokes
 *   tokens on account deletion (the hosted instance); without it, deletion asks the user to revoke in their settings.
 * @property webReturn serve the web-flow return page and handoff (the hosted instance).
 * @property webRedirectUri the return page's public URL (`TORTUGA_PUBLIC_URL` + [SyncPaths.APPLE_RETURN]), which
 *   Apple requires again when a web-flow code is exchanged.
 */
data class AppleConfig(
    val issuer: String,
    val clientIds: List<String>,
    val servicesId: String?,
    val signingKey: AppleSigningKey?,
    val webReturn: Boolean,
    val webRedirectUri: String?,
) {
    /** Every accepted `aud`. */
    val audiences: List<String> get() = clientIds + listOfNotNull(servicesId)
}

/** Team ID, key ID and the `.p8` private key (PKCS#8 PEM) of a Sign in with Apple key. */
class AppleSigningKey(val teamId: String, val keyId: String, val privateKeyPem: String) {
    override fun toString(): String = "AppleSigningKey(teamId=$teamId, keyId=$keyId)"
}

/**
 * Sign in with GitHub through the server's GitHub OAuth App (protocol on `GitHubHandoffRequest` and `SignInRequest` in
 * shared). The client secret stays on the server: it exchanges web-flow codes, resets the apps' tokens and deletes
 * grants.
 *
 * @property url GitHub's web address as apps and browsers reach it (`TORTUGA_GITHUB_URL`, no trailing slash): the
 *   provider's issuer in `/v2/info`, and the issuer GitHub identities are stored under.
 * @property apiUrl GitHub's REST API (`TORTUGA_GITHUB_API_URL`): token reset, emails, grant deletion.
 * @property tokenUrl where web-flow codes are exchanged (`TORTUGA_GITHUB_TOKEN_URL`, by default under [url]).
 */
class GitHubConfig(
    val clientId: String,
    val clientSecret: String,
    val url: String,
    val apiUrl: String,
    val tokenUrl: String,
) {
    override fun toString(): String = "GitHubConfig(clientId=$clientId, url=$url, apiUrl=$apiUrl, tokenUrl=$tokenUrl)"
}

private class EnvReader(private val env: Map<String, String>) {
    fun string(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun required(name: String): String = secret(name) ?: throw IllegalArgumentException("$name is not set")

    /** [name], or the contents of the file named by `${name}_FILE`; one of the two, never both. */
    fun secret(name: String): String? {
        val value = string(name)
        val file = string("${name}_FILE") ?: return value
        require(value == null) { "$name and ${name}_FILE are both set: keep one" }
        return try {
            File(file).readText()
        } catch (e: IOException) {
            throw IllegalArgumentException("${name}_FILE: can't read $file ($e)")
        }.trim().takeIf(String::isNotEmpty)
    }

    fun list(name: String): List<String> = string(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun int(name: String, default: Int): Int =
        string(name)?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$name is not an integer") } ?: default

    fun long(name: String, default: Long): Long =
        string(name)?.let { it.toLongOrNull() ?: throw IllegalArgumentException("$name is not an integer") } ?: default

    fun positiveInt(name: String, default: Int): Int =
        int(name, default).also { require(it > 0) { "$name must be at least 1" } }

    fun boolean(name: String, default: Boolean): Boolean = when (string(name)?.lowercase()) {
        null -> default
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> throw IllegalArgumentException("$name must be true or false")
    }
}
