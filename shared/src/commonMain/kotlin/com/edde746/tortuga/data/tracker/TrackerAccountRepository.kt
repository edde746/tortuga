package com.edde746.tortuga.data.tracker

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.db.SelectConnections
import com.edde746.tortuga.data.db.Tracker_account
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.connections.TrackerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A connected tracker account. Its access token is in the SecretStore, its other tokens on the account server. */
data class TrackerAccount(
    val service: TrackerService,
    val username: String?,
    val scopes: List<String>,
    /**
     * When the account server stored this sign-in, epoch milliseconds by its clock. It names the binding: a reconnect
     * moves it, so work begun under one sign-in can tell that the account it was for is gone.
     */
    val connectedAt: Long,
)

/**
 * A profile's connected trackers, one per service: this device's copy of the account's connections. Their switches
 * (sending at all, content filters) are the profile's synced settings
 * ([com.edde746.tortuga.data.settings.TrackerSwitches]).
 */
class TrackerAccountRepository internal constructor(private val db: Db) {
    fun observeAccounts(profileId: String): Flow<List<TrackerAccount>> =
        db.observeList(db.database.trackerQueries.selectAccounts(profileId)).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun accounts(profileId: String): List<TrackerAccount> =
        db.read { trackerQueries.selectAccounts(profileId).executeAsList().mapNotNull { it.toModel() } }

    /**
     * Every profile's connections on this device, as `profileId to service`; it changes whenever one is made or goes
     * (a sign-out, a deleted profile, sync dropping the account's data). Services this build doesn't know are left out.
     */
    fun observeConnections(): Flow<Set<Pair<String, TrackerService>>> =
        db.observeList(db.database.trackerQueries.selectConnections()).map { rows -> rows.toConnections() }

    suspend fun connections(): Set<Pair<String, TrackerService>> =
        db.read { trackerQueries.selectConnections().executeAsList().toConnections() }

    suspend fun account(profileId: String, service: TrackerService): TrackerAccount? =
        db.read { trackerQueries.selectAccount(profileId, service.id).executeAsOneOrNull()?.toModel() }

    /**
     * Records the account server's [connectedAt] sign-in as the profile's [service] connection. Reconnecting the same
     * service (a new sign-in, maybe to another account) updates the username and scopes and starts a new binding:
     * writes queued under the previous sign-in are dropped, since a queued row carries no account identity and would
     * otherwise replay through the new one.
     */
    suspend fun connect(profileId: String, service: TrackerService, username: String?, scopes: List<String>, connectedAt: Long): TrackerAccount =
        db.write {
            val stored = q.trackerQueries.selectAccount(profileId, service.id).executeAsOneOrNull()
            val row = stored?.copy(username = username, scopes_json = encodeScopes(scopes), connected_at = connectedAt)
                ?: newRow(profileId, service.id, username, encodeScopes(scopes), connectedAt)
            q.trackerQueries.upsertAccount(row)
            if (stored?.connected_at != connectedAt) q.trackerQueries.deleteQueuedForService(profileId, service.id)
            checkNotNull(row.toModel())
        }

    /**
     * Makes this device's connections the account's [connections] (the account server's list). A new one starts as
     * [connect] makes it, a new sign-in to a connected service starts a new binding as [connect] does, and a
     * connection the list doesn't have goes as [disconnect] makes it go. Services this build doesn't know are left
     * out. A profile sync hasn't brought yet keeps its connections here, unseen, until it arrives.
     */
    suspend fun replaceAll(connections: List<TrackerConnection>) = db.write {
        val wanted = connections.filter { TrackerService.fromId(it.service) != null }.associateBy { it.profileId to it.service }
        for (stored in q.trackerQueries.selectAll().executeAsList()) {
            if ((stored.profile_id to stored.service) !in wanted) {
                q.trackerQueries.deleteAccount(stored.profile_id, stored.service)
                q.trackerQueries.deleteQueuedForService(stored.profile_id, stored.service)
            }
        }
        for (connection in wanted.values) {
            val stored = q.trackerQueries.selectAccount(connection.profileId, connection.service).executeAsOneOrNull()
            if (stored?.connected_at == connection.connectedAtMillis && stored.username == connection.username) continue
            val row = stored?.copy(username = connection.username, connected_at = connection.connectedAtMillis)
                ?: newRow(connection.profileId, connection.service, connection.username, encodeScopes(emptyList()), connection.connectedAtMillis)
            q.trackerQueries.upsertAccount(row)
            if (stored?.connected_at != connection.connectedAtMillis) {
                q.trackerQueries.deleteQueuedForService(connection.profileId, connection.service)
            }
        }
    }

    /**
     * Forgets the connection and every write queued for it: a queued row carries no account identity, so it would
     * otherwise replay through whichever account connects to the service next.
     *
     * With [connectedAt], only that binding is forgotten (a session found dead must not sign out the account that
     * replaced it). Returns whether a connection was forgotten.
     */
    suspend fun disconnect(profileId: String, service: TrackerService, connectedAt: Long? = null): Boolean = db.write {
        val stored = q.trackerQueries.selectAccount(profileId, service.id).executeAsOneOrNull()
        if (connectedAt != null && stored?.connected_at != connectedAt) return@write false
        q.trackerQueries.deleteAccount(profileId, service.id)
        q.trackerQueries.deleteQueuedForService(profileId, service.id)
        stored != null
    }
}

private fun newRow(profileId: String, service: String, username: String?, scopesJson: String, connectedAt: Long) = Tracker_account(
    profile_id = profileId,
    service = service,
    username = username,
    scopes_json = scopesJson,
    connected_at = connectedAt,
)

private val scopesSerializer = ListSerializer(String.serializer())

private fun encodeScopes(scopes: List<String>) = Json.encodeToString(scopesSerializer, scopes)

private fun List<SelectConnections>.toConnections() =
    mapNotNull { row -> TrackerService.fromId(row.service)?.let { row.profile_id to it } }.toSet()

private fun Tracker_account.toModel() = TrackerService.fromId(service)?.let { known ->
    TrackerAccount(
        service = known,
        username = username,
        scopes = Json.decodeFromString(scopesSerializer, scopes_json),
        connectedAt = connected_at,
    )
}
