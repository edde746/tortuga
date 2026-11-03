package com.edde746.tortuga.app

import com.edde746.tortuga.crash.FailureBoundary
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.net.closeIdleConnections
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.trackers.BuildTrackerConfig
import com.edde746.tortuga.trackers.installTrackers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * The shared core of one app process ([graph]) and, in the product, its [account] and [sync] engine. Every app builds
 * one at launch, off the main thread (the graph opens the database), and [close]s it when the process ends. Its
 * coroutines run on the main dispatcher, inside one failure boundary ([FailureBoundary]): in the product a coroutine
 * that throws is logged and reported and the app runs on; in a development build and in [local] it ends the process.
 * The platform reports the system's settings ([SystemInputs.report]) first: [product] and [local] throw an
 * [IllegalStateException] before then.
 *
 * - [product]: the device's database, its secret store, the [account] and its [sync] engine
 *   (which is also the playback sessions' [SyncTrigger]). The account gate restores the account, starts the engine
 *   and decides the first screen; the platform's [Startup], begun before this, follows the steps to it.
 * - [local]: a process without an account: an in-memory database (or one of its own), secrets in memory, no account
 *   and no sync, its trackers as its caller installs them. The dev module's development session is one; nothing it
 *   does reaches the device's database, its secret store or a sync server.
 *
 * The product installs the trackers this build has registrations for (`BuildTrackerConfig`: Tortuga's
 * own by default, or the `tortuga.trackers.*` Gradle properties), their connections kept by the account server.
 *
 * The platform calls [onForeground] whenever the app comes to the foreground, [onBackground] whenever it leaves it
 * (hidden, minimized, or running on for audio, picture in picture or a download), and [onNetworkChanged] whenever the
 * network changes. The Mac, where another app being frontmost counts as the background, also calls
 * [onWindowVisibleChanged], which there alone decides whether the app can be seen ([AppGraph.visible]).
 */
class AppBootstrap private constructor(
    val graph: AppGraph,
    val account: SyncAccount?,
    val sync: SyncEngine?,
    /** The boundary of [graph]'s scope, whose failures [com.edde746.tortuga.crash.crashReports] sends. */
    internal val failures: FailureBoundary,
) : AutoCloseable {
    /**
     * The app came to the foreground: its idle connections close (one may have died while it slept), sync catches up,
     * the trackers list their connections and replay queued writes.
     */
    fun onForeground() {
        graph.http.closeIdleConnections()
        graph.setForeground(true)
        sync?.onForeground()
        graph.trackers?.onForeground()
    }

    /** The app went to the background: sync pushes what is pending and stops its periodic pulls. */
    fun onBackground() {
        graph.setForeground(false)
        sync?.onBackground()
    }

    /**
     * The Mac's window can be seen, or no longer can (minimized, hidden, covered whole), whichever app is frontmost and
     * whether or not the app is active: watch together's player follows the room while it can ([AppGraph.visible]).
     */
    fun onWindowVisibleChanged(visible: Boolean) {
        graph.setWindowVisible(visible)
    }

    /**
     * The device's network changed ([AppGraph.network]): [online] when some network is up, [metered] when it costs per
     * byte. The idle connections close, since they went out on the old network. Coming back online replays the trackers'
     * queued writes.
     */
    fun onNetworkChanged(online: Boolean, metered: Boolean) {
        graph.http.closeIdleConnections()
        val wasOnline = graph.network.value.online
        graph.setNetwork(NetworkState(online, metered))
        if (graph.network.value.online && !wasOnline) graph.trackers?.onNetworkRestored()
    }

    /** Stops sync and closes the graph (its HTTP client and database). */
    override fun close() {
        sync?.stop()
        graph.close()
    }

    companion object {
        /**
         * @param database the device's database (`DatabaseFactory()` on Apple platforms, the file in
         *   `DatabaseFactory.defaultDirectory()` on desktop).
         * @param device what the server lists this device as.
         * @param cacheDirectory the platform's cache directory for files the app can download again (the trackers'
         *   anime mapping datasets): next to the database on desktop, `cacheDir` on Android, Caches on Apple platforms.
         * @param startup the start the splash shows, which the platform began first: opening the database reports a
         *   migration to it, and the platform hands it to [RootSession].
         * @param development a development build (Android's debug build, Apple's Debug configuration): a coroutine that
         *   throws ends the process instead of being reported ([FailureBoundary]).
         */
        fun product(
            database: DatabaseFactory,
            secrets: SecretStore,
            flavor: BuildFlavor,
            appVersion: String,
            device: DeviceDescriptor,
            cacheDirectory: String,
            startup: Startup,
            development: Boolean = false,
        ): AppBootstrap {
            SystemInputs.requireReported()
            val failures = FailureBoundary(crash = development)
            val scope = MainScope() + failures
            // The engine needs the graph's LocalData, the graph needs the engine as its SyncTrigger.
            val trigger = ForwardingSyncTrigger()
            val data = LocalData(database) { startup.begin(StartupStep.MIGRATION) }
            val graph = AppGraph(data, secrets, trigger, flavor, device, appVersion, TortugaHttpClient.create(appVersion), scope, Clock.System)
            val account = SyncAccount(secrets, graph.data, graph.http, device)
            val engine = SyncEngine(account, graph.data, scope)
            trigger.target = engine
            val trackers = graph.installTrackers(BuildTrackerConfig.config, cacheDirectory, account)
            // The account's tracker connections: listed once the account is restored or signed in while the app is in
            // the foreground (in the background, at its first onForeground), and when a pull says they changed.
            scope.launch {
                account.state.filter { it is AccountState.SignedIn }.collect { if (graph.foreground.value) trackers.refreshConnections() }
            }
            scope.launch { engine.trackersVersions.collect { trackers.onTrackersVersion(it) } }
            return AppBootstrap(graph, account, engine, failures)
        }

        /**
         * A process without an account (a development session, a UI test), whose coroutine that throws ends the process
         * ([FailureBoundary]).
         *
         * @param database an in-memory database (`DatabaseFactory.inMemory()`), or a directory of its own.
         * @param device this device, as the product would list it (a process without an account has no server).
         * @param installTrackers installs the graph's trackers (`AppGraph.installTrackers`).
         */
        fun local(
            database: DatabaseFactory,
            flavor: BuildFlavor,
            appVersion: String,
            device: DeviceDescriptor,
            installTrackers: (AppGraph) -> Unit,
        ): AppBootstrap {
            SystemInputs.requireReported()
            val failures = FailureBoundary(crash = true)
            val graph = AppGraph(database, MemorySecretStore(), SyncTrigger.None, flavor, device, appVersion, scope = MainScope() + failures)
            installTrackers(graph)
            return AppBootstrap(graph, account = null, sync = null, failures)
        }
    }
}

/** [SyncTrigger] forwarding to the sync engine once it exists (the engine is built after the graph it syncs). */
private class ForwardingSyncTrigger : SyncTrigger {
    @Volatile var target: SyncTrigger = SyncTrigger.None

    override fun setPlaying(playing: Boolean) = target.setPlaying(playing)

    override fun flushNow() = target.flushNow()
}
