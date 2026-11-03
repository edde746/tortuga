@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.sync.engine.SyncEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * One app install against a [FakeSyncServer]: its secret store, database (in memory unless [database] is given), account
 * and engine, all on the test's virtual clock and dispatcher so scheduling runs in virtual time. [clockSkewMillis] sets
 * the device's clock that far ahead of the server's (behind if negative). The account keeps its session in [store],
 * [secrets] unless another is given (a [FailingSecretStore] around it).
 */
internal class TestDevice(
    val name: String,
    server: FakeSyncServer,
    test: TestScope,
    engineScope: CoroutineScope,
    val secrets: MemorySecretStore = MemorySecretStore(),
    config: SyncEngineConfig = SyncEngineConfig(),
    clockSkewMillis: Long = 0,
    database: DatabaseFactory = inMemoryDatabase(),
    store: SecretStore = secrets,
) : AutoCloseable {
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    val clock: () -> Long = { FakeSyncServer.NOW + test.testScheduler.currentTime + clockSkewMillis }
    init {
        server.clock = { FakeSyncServer.NOW + test.testScheduler.currentTime }
    }

    val data = LocalData(database, clock, dispatcher, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
    val account = SyncAccount(
        store, data, DeviceDescriptor(name, DevicePlatform.LINUX), SyncApi(server.client(dispatcher)), clock, 2_000,
    )
    val engine = SyncEngine(account, data, engineScope, config, clock, dispatcher)

    override fun close() {
        engine.stop()
        data.close()
    }

    companion object {
        /** The fake server's "ID token" is the identity's subject. */
        fun credential(subject: String = "alice") = ProviderCredential(IdentityProvider.OIDC, subject, nonce = null)
    }
}

/**
 * A platform secret store that fails as Android's does when `commit()` can't write (a full disk), or when the Keystore
 * fails for a moment: every read while [failReads], every write while [failWrites], every delete while [failDeletes]
 * throws [IllegalStateException]. Its entries are [memory]'s.
 */
internal class FailingSecretStore(val memory: MemorySecretStore = MemorySecretStore()) : SecretStore {
    var failReads = false
    var failWrites = false
    var failDeletes = false

    override suspend fun get(key: String): ByteArray? {
        check(!failReads) { "Keystore operation failed" }
        return memory.get(key)
    }

    override suspend fun put(key: String, value: ByteArray) {
        check(!failWrites) { "commit() failed" }
        memory.put(key, value)
    }

    override suspend fun delete(key: String) {
        check(!failDeletes) { "commit() failed" }
        memory.delete(key)
    }
}
