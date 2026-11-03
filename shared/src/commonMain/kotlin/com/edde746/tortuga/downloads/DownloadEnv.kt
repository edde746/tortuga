package com.edde746.tortuga.downloads

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.data.downloads.DownloadRepository
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.StreamResolver
import com.edde746.tortuga.playback.subtitles.SubtitleResolver
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** What the download engine's parts share: the graph's services, the platform and the device's settings as they are now. */
internal class DownloadEnv(
    val repo: DownloadRepository,
    val platform: DownloadPlatform,
    val files: DownloadFiles,
    val resolver: StreamResolver,
    val aggregator: AddonAggregator,
    val subtitles: SubtitleResolver,
    val http: HttpClient,
    val settings: SettingsRepository,
    val deviceSettings: DeviceSettingsRepository,
    val library: LibraryRepository,
    val streamHistory: StreamHistoryRepository,
    /** A profile's installed addons, in order. */
    val addons: suspend (profileId: String) -> List<Descriptor>,
    val network: StateFlow<NetworkState>,
    /** `Tortuga/<version>`, sent with every transfer unless the stream asks for its own. */
    val userAgent: String,
    /** Where file operations run. */
    val io: CoroutineContext,
    val clock: Clock,
) {
    /** This device's settings, as last read (the manager follows them); null before the first read. */
    val device = MutableStateFlow<DeviceSettings?>(null)

    /** Wi-Fi only as it applies: the setting, else the platform's default, else off. */
    fun wifiOnly(): Boolean = device.value?.downloadWifiOnly ?: platform.wifiOnlyDefault ?: false

    /** The network lets downloads run: online, and unmetered when [wifiOnly]. */
    fun networkAllows(state: NetworkState = network.value): Boolean = state.online && !(wifiOnly() && state.metered)

    /** The directory of the location [id] now; null when it's gone (an SD card removed). Asks the platform on [io]. */
    suspend fun locationPath(id: String): String? =
        withContext(io) { platform.locations() }.firstOrNull { it.id == id }?.path ?: DownloadLocation.folderPath(id)

    /** Runs a file operation on [io]. */
    suspend fun <T> io(block: DownloadFiles.() -> T): T = withContext(io) { files.block() }

    /**
     * Creates [relativeDirectory] under [directory] (the location's), the location's own directory included; a top
     * directory created now is excluded from backups ([DownloadPlatform.excludeFromBackup]). False when that fails.
     */
    fun createDirectories(directory: String, relativeDirectory: String): Boolean {
        val top = child(directory, relativeDirectory.substringBefore('/'))
        val existed = files.exists(top)
        if (!files.createDirectories(child(directory, relativeDirectory))) return false
        if (!existed) platform.excludeFromBackup(top.toString())
        return true
    }

    /** The addons of [id]'s owners, each once, in their order (the first owner's first). */
    suspend fun ownerAddons(id: Long): List<Descriptor> =
        repo.owners(id).flatMap { addons(it) }.distinctBy { it.transportUrl }
}
