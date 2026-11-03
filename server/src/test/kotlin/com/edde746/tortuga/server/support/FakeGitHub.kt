package com.edde746.tortuga.server.support

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ExternalServicesBuilder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GitHub for the server under test, through Ktor's external services, behaving as `server/dev/fake_github.py` does for
 * the calls the server makes: the web-flow code exchange at [URL]`/login/oauth/access_token`, and at [API_URL] the
 * token reset, the email list and the grant deletion, each checking the app's credentials. [authorize] stands in for
 * the browser's trip through GitHub (an auto-approved authorization) and [token] for a device flow or another app.
 */
class FakeGitHub {
    private class Token(val login: String, val clientId: String, val scopes: List<String>)

    private val lock = Any()
    private val ids = HashMap<String, Long>()
    private val codes = HashMap<String, Token>()
    private val tokens = LinkedHashMap<String, Token>()
    private val grants = HashSet<Pair<String, String>>()
    private var nextId = 583_231L

    /** Every token and code issued, for checks that none reached the database or the logs. */
    val issued = CopyOnWriteArrayList<String>()

    /** The tokens the server deleted grants with, in order. */
    val grantDeletions = CopyOnWriteArrayList<String>()

    /** While set, every endpoint answers 503. */
    @Volatile var down = false

    fun userId(login: String): Long = synchronized(lock) { ids.getOrPut(login) { nextId++ } }

    /** The code GitHub's redirect hands the return page once [login] has authorized [clientId] (scope `user:email`). */
    fun authorize(login: String, clientId: String = CLIENT_ID): String = synchronized(lock) {
        grant(login, clientId)
        "code${UUID.randomUUID().toString().replace("-", "")}".also {
            codes[it] = Token(login, clientId, listOf(SCOPE))
            issued += it
        }
    }

    /** A token GitHub issued to [clientId] for [login]: a device flow's result, or another app's token. */
    fun token(login: String, clientId: String = CLIENT_ID): String = synchronized(lock) {
        grant(login, clientId)
        issue(Token(login, clientId, listOf(SCOPE)))
    }

    fun isLive(token: String): Boolean = synchronized(lock) { token in tokens }

    /** Whose [token] is, while it lives. */
    fun loginOf(token: String): String? = synchronized(lock) { tokens[token]?.login }

    /** [login]'s live tokens for [clientId], oldest first. */
    fun liveTokens(login: String, clientId: String = CLIENT_ID): List<String> = synchronized(lock) {
        tokens.filterValues { it.login == login && it.clientId == clientId }.keys.toList()
    }

    fun hasGrant(login: String, clientId: String = CLIENT_ID): Boolean = synchronized(lock) { (login to clientId) in grants }

    /** [login] removes the app at GitHub (Settings → Applications): the grant and its tokens end. */
    fun removeGrant(login: String, clientId: String = CLIENT_ID) = synchronized(lock) { endGrant(login, clientId) }

    fun mount(services: ExternalServicesBuilder) {
        services.hosts(URL) {
            routing {
                post("/login/oauth/access_token") {
                    if (call.isDown()) return@post
                    val form = call.receiveParameters()
                    val clientId = form["client_id"].orEmpty()
                    val answer = synchronized(lock) {
                        val code = codes.remove(form["code"])
                        when {
                            clients[clientId] == null || clients[clientId] != form["client_secret"] ->
                                buildJsonObject { put("error", "incorrect_client_credentials") }
                            code == null || code.clientId != clientId -> buildJsonObject { put("error", "bad_verification_code") }
                            else -> buildJsonObject {
                                put("access_token", issue(code))
                                put("token_type", "bearer")
                                put("scope", code.scopes.joinToString(","))
                            }
                        }
                    }
                    call.respondJson(answer.toString())
                }
            }
        }
        services.hosts(API_URL) {
            routing {
                patch("/applications/{clientId}/token") {
                    if (call.isDown() || !call.appAuthorized()) return@patch
                    val clientId = call.parameters["clientId"]!!
                    val sent = call.accessToken()
                    val reset = synchronized(lock) {
                        val token = tokens[sent]?.takeIf { it.clientId == clientId } ?: return@synchronized null
                        tokens.remove(sent)
                        issue(token) to token
                    }
                    if (reset == null) return@patch call.respondJson("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                    val (newToken, token) = reset
                    val answer = buildJsonObject {
                        put("token", newToken)
                        putJsonArray("scopes") { token.scopes.forEach { add(it) } }
                        putJsonObject("user") {
                            put("login", token.login)
                            put("id", userId(token.login))
                        }
                        putJsonObject("app") { put("client_id", clientId) }
                    }
                    call.respondJson(answer.toString())
                }
                get("/user/emails") {
                    if (call.isDown()) return@get
                    val sent = call.request.headers[HttpHeaders.Authorization]?.substringAfter(' ')
                    val token = synchronized(lock) { tokens[sent] }
                        ?: return@get call.respondJson("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
                    if (SCOPE !in token.scopes) return@get call.respondJson("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                    val answer = buildJsonArray {
                        // An older address first: only the primary verified one is the account's.
                        addJsonObject {
                            put("email", "${token.login}@old.fake")
                            put("primary", false)
                            put("verified", true)
                        }
                        addJsonObject {
                            put("email", email(token.login))
                            put("primary", true)
                            put("verified", true)
                            put("visibility", "private")
                        }
                    }
                    call.respondJson(answer.toString())
                }
                delete("/applications/{clientId}/grant") {
                    if (call.isDown() || !call.appAuthorized()) return@delete
                    val clientId = call.parameters["clientId"]!!
                    val sent = call.accessToken()
                    grantDeletions += sent
                    val ended = synchronized(lock) {
                        val token = tokens[sent]?.takeIf { it.clientId == clientId } ?: return@synchronized false
                        endGrant(token.login, clientId)
                        true
                    }
                    if (ended) call.respond(HttpStatusCode.NoContent) else call.respondJson("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                }
            }
        }
    }

    private fun grant(login: String, clientId: String) {
        userId(login)
        grants += login to clientId
    }

    private fun issue(token: Token): String =
        "gho_${UUID.randomUUID().toString().replace("-", "")}".also {
            tokens[it] = token
            issued += it
        }

    private fun endGrant(login: String, clientId: String) {
        grants -= login to clientId
        tokens.values.removeAll { it.login == login && it.clientId == clientId }
    }

    private suspend fun ApplicationCall.isDown(): Boolean {
        if (down) respondJson("""{"message":"Service unavailable"}""", HttpStatusCode.ServiceUnavailable)
        return down
    }

    /** Basic auth with the path's client ID and its secret, as GitHub's `/applications/{client_id}/…` require. */
    private suspend fun ApplicationCall.appAuthorized(): Boolean {
        val clientId = parameters["clientId"]
        val basic = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Basic ") }?.removePrefix("Basic ")
        val credentials = basic?.let { runCatching { Base64.getDecoder().decode(it).decodeToString() }.getOrNull() }
        val ok = clientId != null && clients[clientId] != null && credentials == "$clientId:${clients[clientId]}"
        if (!ok) respondJson("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
        return ok
    }

    private suspend fun ApplicationCall.accessToken(): String =
        ((Json.parseToJsonElement(receiveText()) as JsonObject)["access_token"] as JsonPrimitive).content

    private suspend fun ApplicationCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(json, ContentType.Application.Json, status)

    companion object {
        const val URL = "https://github.test"
        const val API_URL = "https://api.github.test"
        const val CLIENT_ID = "tortuga-github"
        const val CLIENT_SECRET = "tortuga-github-secret"

        /** Another OAuth app, whose tokens Tortuga's server must refuse. */
        const val OTHER_CLIENT_ID = "other-app"
        const val OTHER_CLIENT_SECRET = "other-app-secret"

        const val SCOPE = "user:email"

        private val clients = mapOf(CLIENT_ID to CLIENT_SECRET, OTHER_CLIENT_ID to OTHER_CLIENT_SECRET)

        /** [login]'s primary verified address. */
        fun email(login: String): String = "$login@users.noreply.fake"
    }
}
