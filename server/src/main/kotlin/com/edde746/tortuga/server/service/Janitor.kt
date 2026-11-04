package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.uuid
import java.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Deletes expired nonces, used-token markers, device links, Apple and GitHub handoffs and idle sessions every
 * [interval], and drops the tracker sign-in relay's expired sign-ins ([TrackerOAuthRelay.purgeExpired]); an expired
 * session's notice stream ends with it.
 */
class Janitor(
    private val db: Database,
    private val events: SyncEvents,
    private val trackerOAuth: TrackerOAuthRelay,
    private val clock: Clock,
    private val interval: Duration,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(interval)
            try {
                sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cleanup failed", e)
            }
        }
    }

    suspend fun sweep() {
        trackerOAuth.purgeExpired()
        val now = clock.instant()
        db.transaction {
            update("DELETE FROM auth_nonces WHERE expires_at <= ?", now)
            update("DELETE FROM used_id_tokens WHERE expires_at <= ?", now)
            update("DELETE FROM device_links WHERE expires_at <= ?", now)
            update("DELETE FROM apple_handoffs WHERE expires_at <= ?", now)
            update("DELETE FROM github_handoffs WHERE expires_at <= ?", now)
            val ended = query("DELETE FROM sessions WHERE refresh_expires_at <= ? RETURNING account_id, id", now) {
                it.uuid("account_id") to it.uuid("id")
            }
            if (ended.isNotEmpty()) afterCommit { ended.forEach { (account, session) -> events.endSession(account, session) } }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(Janitor::class.java)
    }
}
