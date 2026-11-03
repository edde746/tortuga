package com.edde746.tortuga.trackers

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.SessionVault
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * An access token of a connection, as the account server handed it to this device. [connectedAt] names the sign-in
 * it belongs to ([com.edde746.tortuga.data.tracker.TrackerAccount.connectedAt]); [expiresAt] is epoch seconds by
 * this device's clock, `null` when the token doesn't expire.
 */
@Serializable
internal data class TrackerToken(val connectedAt: Long, val accessToken: String, val expiresAt: Long?) {
    /** The token expires within the account server's refresh window: ask the server for the next one. */
    fun needsRefresh(nowEpochSeconds: Long): Boolean = expiresAt != null && nowEpochSeconds >= expiresAt - TrackerSession.REFRESH_WINDOW_SECONDS

    fun expired(nowEpochSeconds: Long): Boolean = expiresAt != null && nowEpochSeconds >= expiresAt

    override fun toString(): String = "TrackerToken(connectedAt=$connectedAt, expiresAt=$expiresAt)"
}

/**
 * This device's copy of its tracker connections' access tokens (connections belong to the account, per profile, and
 * the account server holds and refreshes their tokens). A token is kept until it expires, so a restart doesn't
 * need the server; refresh tokens never reach the device.
 *
 * One `SecretStore` entry per profile and service, JSON-encoded, plus an index of those entries (the store can't list
 * its keys), so tokens whose connection is gone can be cleared.
 *
 * The store's own failures (Android's Keystore or a failed `commit()`, a Keychain status, libsecret, Credential
 * Manager) come out of [load], [save] and [clear] as [TrackerException.AccountServer]s with [AccountError.Unexpected]
 * (status 0, code [SessionVault.SECRET_STORE_FAILED]), as the session's do: like an account server that can't be
 * asked, they say nothing about a write, so it stays queued with its attempts, and a list shows its error. [stored]
 * throws the store's failure as it is: only the cleanup of unused tokens lists them, and it logs it.
 */
internal class TrackerTokenStore(private val secrets: SecretStore) {
    /** The stored token, or `null` when there is none or it no longer decodes. */
    suspend fun load(profileId: String, service: TrackerService): TrackerToken? {
        val bytes = guarded(service, "read") { secrets.get(key(profileId, service)) } ?: return null
        return try {
            json.decodeFromString(TrackerToken.serializer(), bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            // SerializationException included.
            Log.w(TAG) { "stored ${service.id} token unreadable, asking the account server again" }
            null
        }
    }

    suspend fun save(profileId: String, service: TrackerService, token: TrackerToken) = guarded(service, "write") {
        secrets.put(key(profileId, service), json.encodeToString(TrackerToken.serializer(), token).encodeToByteArray())
        val index = index()
        val entry = IndexEntry(profileId, service.id)
        if (entry !in index) writeIndex(index + entry)
    }

    suspend fun clear(profileId: String, service: TrackerService) = guarded(service, "delete") {
        secrets.delete(key(profileId, service))
        val index = index()
        val entry = IndexEntry(profileId, service.id)
        if (entry in index) writeIndex(index - entry)
    }

    /** Every profile and service with a stored token. */
    suspend fun stored(): Set<Pair<String, TrackerService>> =
        index().mapNotNull { entry -> TrackerService.fromId(entry.service)?.let { entry.profileId to it } }.toSet()

    private suspend fun index(): List<IndexEntry> {
        val bytes = secrets.get(INDEX_KEY) ?: return emptyList()
        return try {
            json.decodeFromString(indexSerializer, bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG) { "tracker token index unreadable, starting over" }
            emptyList()
        }
    }

    private suspend fun writeIndex(index: List<IndexEntry>) {
        if (index.isEmpty()) secrets.delete(INDEX_KEY) else secrets.put(INDEX_KEY, json.encodeToString(indexSerializer, index).encodeToByteArray())
    }

    private suspend fun <T> guarded(service: TrackerService, operation: String, call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, e) { "the secret store's $operation of the ${service.id} token failed" }
        throw TrackerException.AccountServer(service, AccountException(AccountError.Unexpected(0, SessionVault.SECRET_STORE_FAILED), e))
    }

    @Serializable
    private data class IndexEntry(val profileId: String, val service: String)

    internal companion object {
        private const val TAG = "trackers"
        private const val INDEX_KEY = "tracker.index"
        private val json = Json { ignoreUnknownKeys = true }
        private val indexSerializer = ListSerializer(IndexEntry.serializer())

        /** `tracker.<service>.<profileId>`: short ASCII, as `SecretStore` keys must be. */
        fun key(profileId: String, service: TrackerService): String = "tracker.${service.id}.$profileId"
    }
}
