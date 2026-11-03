package com.edde746.tortuga.sync.account

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.api.SessionTokens
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** This device's session on a sync server, as kept in the `SecretStore` (entry `account.session`). */
@Serializable
internal data class StoredSession(val serverUrl: String, val accountId: String, val tokens: StoredTokens) {
    /** The tokens' fields never reach logs. */
    override fun toString(): String = "StoredSession(accountId=$accountId, deviceId=${tokens.deviceId})"
}

/**
 * A session's tokens as this device keeps them ([SessionTokens.received]).
 *
 * @property accessExpiresAtMillis when the access token runs out by this device's clock: the lifetime the server
 *   granted, added to the clock when the tokens arrived.
 */
@Serializable
internal data class StoredTokens(
    val deviceId: String,
    val accessToken: String,
    val accessExpiresAtMillis: Long,
    val refreshToken: String,
) {
    override fun toString(): String = "StoredTokens(deviceId=$deviceId)"
}

/** These tokens as kept on this device, which received them at [now] by its own clock. */
internal fun SessionTokens.received(now: Long) = StoredTokens(deviceId, accessToken, now + accessExpiresInMillis, refreshToken)

/**
 * The session's entry in the platform [SecretStore]. The store's own failures (Android's Keystore or a failed
 * `commit()`, a Keychain status, libsecret) come out as [AccountException]s with [AccountError.Unexpected] (status 0,
 * code [SECRET_STORE_FAILED]), so every caller of the account layer handles them as it handles the server's.
 */
internal class SessionVault(private val secrets: SecretStore) {
    suspend fun load(): StoredSession? {
        val bytes = guarded("read") { secrets.get(SESSION_ENTRY) } ?: return null
        return try {
            storageJson.decodeFromString(StoredSession.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            Log.w("SessionVault", e) { "Dropping an unreadable session entry" }
            clear()
            null
        }
    }

    suspend fun store(session: StoredSession) = guarded("write") {
        secrets.put(SESSION_ENTRY, storageJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
    }

    suspend fun clear() = guarded("delete") { secrets.delete(SESSION_ENTRY) }

    private suspend fun <T> guarded(operation: String, call: suspend () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("SessionVault", e) { "The secret store's $operation of the session failed" }
        throw AccountException(AccountError.Unexpected(0, SECRET_STORE_FAILED), e)
    }

    companion object {
        private const val SESSION_ENTRY = "account.session"

        /** [AccountError.Unexpected.code] of a failure of the platform's secret store. */
        const val SECRET_STORE_FAILED = "secret_store_failed"
    }
}

private val storageJson = Json { ignoreUnknownKeys = true }
