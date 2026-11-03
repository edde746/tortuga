package com.edde746.tortuga.server.auth

import com.edde746.tortuga.server.GitHubConfig
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.http.bodyAsTextAtMost
import com.edde746.tortuga.sync.api.ApiErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.prepareForm
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * GitHub's OAuth endpoints with the server's GitHub OAuth App ([GitHubConfig]): exchanges web-flow codes, resets the
 * apps' tokens at sign-in (only a token issued to this app resets, and the answer names its user), reads the user's
 * primary verified email, and deletes the grant when GitHub is unlinked or the account deleted. Exists only when GitHub
 * is configured. Its logs name the call and GitHub's status or failure, never a token, code, email or the secret.
 */
class GitHubClient(private val http: HttpClient, private val config: GitHubConfig) {
    /** The issuer GitHub identities are stored under: GitHub's web address. */
    val issuer: String get() = config.url

    /** What a token reset gave: the [token] that replaces the one the app sent, and its user's numeric [userId]. */
    class ResetToken(val token: String, val userId: Long)

    /**
     * Exchanges a web-flow [code] for an access token at [GitHubConfig.tokenUrl]. GitHub refusing the code (expired,
     * used, not this app's) is 401 [ApiErrorCode.INVALID_ID_TOKEN]; GitHub unreachable or failing is 502.
     */
    suspend fun exchangeCode(code: String): String {
        val answer = send("code exchange") {
            http.prepareForm(
                url = config.tokenUrl,
                formParameters = parameters {
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret)
                    append("code", code)
                },
            ) { header(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
        } ?: throw unavailable()
        if (answer.status != HttpStatusCode.OK) throw unavailable("code exchange", answer.status)
        val json = parseObject(answer.body) ?: throw unavailable("code exchange", answer.status)
        json.string("access_token")?.let { return it }
        // A refused code is a 200 with GitHub's error code (bad_verification_code, incorrect_client_credentials, ...).
        val error = json.string("error") ?: throw unavailable("code exchange", answer.status)
        log.info("GitHub refused a web-flow code: {}", error)
        throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN, "GitHub refused the code")
    }

    /**
     * Resets [accessToken] at GitHub (`PATCH /applications/{client_id}/token`), which ends it and answers a new one
     * with its user. 401 [ApiErrorCode.INVALID_ID_TOKEN] when GitHub doesn't hold it as this app's token (another
     * app's, revoked, made up); 502 when GitHub is unreachable or fails.
     */
    suspend fun resetToken(accessToken: String): ResetToken {
        val answer = send("token reset") {
            http.prepareRequest("${config.apiUrl}/applications/${config.clientId}/token") {
                method = HttpMethod.Patch
                appCredentials(accessToken)
            }
        } ?: throw unavailable()
        if (answer.status == HttpStatusCode.NotFound || answer.status == HttpStatusCode.UnprocessableEntity) {
            throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN, "GitHub refused the token")
        }
        if (answer.status != HttpStatusCode.OK) throw unavailable("token reset", answer.status)
        val json = parseObject(answer.body)
        val token = json?.string("token")
        val userId = ((json?.get("user") as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
        if (token == null || userId == null) throw unavailable("token reset", answer.status)
        return ResetToken(token, userId)
    }

    /**
     * The user's primary verified email, read with [accessToken] (`GET /user/emails`, scope `user:email`); null when
     * there is none or GitHub doesn't answer with the list.
     */
    suspend fun primaryEmail(accessToken: String): String? {
        val answer = send("emails request") {
            http.prepareRequest("${config.apiUrl}/user/emails") {
                apiHeaders()
                bearerAuth(accessToken)
            }
        } ?: return null
        if (answer.status != HttpStatusCode.OK) return null
        val emails = parse(answer.body) as? JsonArray ?: return null
        return emails.firstNotNullOfOrNull { element ->
            val entry = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val verified = entry.flag("primary") && entry.flag("verified")
            entry.string("email")?.takeIf { verified && it.length <= MAX_EMAIL_LENGTH }
        }
    }

    /**
     * Deletes the user's grant of this app with [accessToken] (`DELETE /applications/{client_id}/grant`): every token
     * GitHub issued to the app for that user ends, and Tortuga leaves the user's authorized OAuth apps. True when
     * GitHub answered 204; anything else (the user already removed Tortuga, GitHub ended the token, GitHub unreachable)
     * is logged and false.
     */
    suspend fun deleteGrant(accessToken: String): Boolean {
        val answer = send("grant deletion") {
            http.prepareRequest("${config.apiUrl}/applications/${config.clientId}/grant") {
                method = HttpMethod.Delete
                appCredentials(accessToken)
            }
        } ?: return false
        val deleted = answer.status == HttpStatusCode.NoContent
        if (!deleted) log.warn("GitHub grant deletion answered {}", answer.status.value)
        return deleted
    }

    /** The app's client ID and secret (Basic) with [accessToken] in a JSON body, as `/applications/{client_id}/…` want. */
    private fun HttpRequestBuilder.appCredentials(accessToken: String) {
        apiHeaders()
        basicAuth(config.clientId, config.clientSecret)
        val body = buildJsonObject { put("access_token", accessToken) }.toString()
        setBody(TextContent(body, ContentType.Application.Json))
    }

    private fun HttpRequestBuilder.apiHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    /**
     * [request]'s status and body (at most [MAX_TOKEN_ANSWER_BYTES]); null, logged, when GitHub can't be reached or
     * answers more. The failure's text names the URL and what failed, never a token: tokens go in headers and bodies.
     */
    private suspend fun send(what: String, request: suspend () -> HttpStatement): Answer? = try {
        request().execute { Answer(it.status, it.bodyAsTextAtMost("GitHub's $what", MAX_TOKEN_ANSWER_BYTES)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("GitHub {} failed: {}", what, e.toString())
        null
    }

    private class Answer(val status: HttpStatusCode, val body: String)

    private companion object {
        val log = LoggerFactory.getLogger(GitHubClient::class.java)

        /** GitHub's REST API version the requests ask for. */
        const val API_VERSION = "2022-11-28"

        /** An address longer than this isn't kept (as ID tokens' emails, [IdTokenVerifier]). */
        const val MAX_EMAIL_LENGTH = 320

        /** [body] as JSON; null when it isn't. The parser's message quotes the input, which may hold a token: unlogged. */
        fun parse(body: String): JsonElement? = try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            null
        }

        fun parseObject(body: String): JsonObject? = parse(body) as? JsonObject

        fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true

        /** GitHub unreachable: [send] logged why. */
        fun unavailable() =
            ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "GitHub unreachable")

        fun unavailable(what: String, status: HttpStatusCode): ApiException {
            log.warn("GitHub {} answered {}", what, status.value)
            return ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "GitHub $what failed")
        }
    }
}
