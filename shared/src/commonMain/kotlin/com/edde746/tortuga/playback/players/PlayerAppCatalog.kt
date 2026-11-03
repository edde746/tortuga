package com.edde746.tortuga.playback.players

import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The player apps of one running app ([launcher]'s platform): the players detected on this device, then the custom
 * players of `DeviceSetting.CustomPlayers` as this platform takes them ([PlayerAppLauncher.customPlayers]), with the
 * device's settings they come with (the player chosen, its name, the prompts).
 *
 * Detection runs once as the catalog starts and again on [detect] (the Player page opening); its answer is kept until
 * the next one. Call everything on the main thread; [release] ends it.
 */
internal class PlayerAppCatalog(
    val launcher: PlayerAppLauncher,
    deviceSettings: DeviceSettingsRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** The last detection's players; null until the first answer. */
    private var detected: List<PlayerApp>? = null
    private var detection = 0

    private val mutableSettings = MutableStateFlow<DeviceSettings?>(null)
    private val mutablePlayers = MutableStateFlow<List<PlayerApp>>(emptyList())
    private val mutableDetecting = MutableStateFlow(false)
    private val mutableKnown = MutableStateFlow(false)

    /** This device's settings as last read; null until read. */
    val settings: StateFlow<DeviceSettings?> = mutableSettings.asStateFlow()

    /** The detected players (the chooser among them, where the platform has one), then the custom ones. */
    val players: StateFlow<List<PlayerApp>> = mutablePlayers.asStateFlow()

    /** A detection runs ([detect]). */
    val detecting: StateFlow<Boolean> = mutableDetecting.asStateFlow()

    /** [players] is complete: a detection answered and the settings were read. */
    val known: StateFlow<Boolean> = mutableKnown.asStateFlow()

    init {
        scope.launch {
            deviceSettings.observe().collect {
                mutableSettings.value = it
                publish()
            }
        }
        detect()
    }

    /** Looks for this device's players again; [players] follows the answer. */
    fun detect() {
        val run = ++detection
        mutableDetecting.value = true
        try {
            // The answer may come from any thread.
            launcher.detect { players -> scope.launch { found(run, players) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "detecting the player apps failed" }
            found(run, emptyList())
        }
    }

    /** The player [id] among [players], or null. */
    fun find(id: String): PlayerApp? = players.value.firstOrNull { it.id == id }

    /**
     * What [id] is called: its name among [players], else the name kept with the setting when it is the one chosen
     * (a player not found any more), else [id] itself.
     */
    fun name(id: String): String =
        find(id)?.name ?: settings.value?.takeIf { it.player == id }?.playerName ?: id

    /** Waits until [known]. */
    suspend fun awaitKnown() {
        known.first { it }
    }

    fun release() {
        scope.cancel()
    }

    private fun found(run: Int, players: List<PlayerApp>) {
        // An earlier detection's late answer: the current one's is the one kept.
        if (run != detection) return
        detected = players
        mutableDetecting.value = false
        publish()
    }

    private fun publish() {
        val settings = settings.value
        val kind = launcher.customPlayers
        val custom = if (settings == null || kind == null) emptyList() else settings.customPlayers.map { it.asPlayerApp(kind) }
        mutablePlayers.value = detected.orEmpty() + custom
        mutableKnown.value = detected != null && settings != null
    }

    private companion object {
        const val TAG = "player-apps"
    }
}

/**
 * This custom player as a [PlayerApp] of a platform whose custom players are [kind]: a program passes the start and
 * the subtitle when its arguments have their placeholders, and reports nothing; a URL template passes the URL only; an
 * Android package is started as the app it names is ([AndroidPlayers.of]): a known one with its own extras, any other
 * with MX Player's from the beginning, answering with where it stopped.
 */
internal fun CustomPlayer.asPlayerApp(kind: CustomPlayerKind): PlayerApp = PlayerApp(
    id = PlayerApp.CUSTOM_PREFIX + id,
    name = name,
    kind = PlayerAppKind.CUSTOM,
    capabilities = when (kind) {
        CustomPlayerKind.COMMAND -> PlayerAppCapabilities(
            startPosition = PlayerAppTemplates.START in arguments,
            subtitles = PlayerAppTemplates.SUBTITLE in arguments,
            progress = PlayerAppProgress.NONE,
        )
        CustomPlayerKind.URL_TEMPLATE -> PlayerAppCapabilities.URL_ONLY
        CustomPlayerKind.PACKAGE -> AndroidPlayers.of(command.trim()).api.capabilities
    },
    custom = this,
)
