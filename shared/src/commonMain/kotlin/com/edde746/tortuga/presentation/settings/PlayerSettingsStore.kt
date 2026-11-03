package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCatalog
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppTemplates
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Settings → Playback → Player ([PlayerSettingsUiState]): the player this device plays in, chosen among Tortuga, the
 * player apps detected here and the custom players added here (`DeviceSetting.Player`, `DeviceSetting.CustomPlayers`).
 *
 * - The players are [catalog]'s; opening the page looks for them again.
 * - The chosen player's name is kept with the choice (`DeviceSetting.PlayerName`), so one that isn't found any more
 *   still shows by name ("VLC (not found)"), and plays fail with Play in Tortuga on offer until another is chosen.
 * - Custom players take what this platform's do ([CustomPlayerKind]); the form refuses one without a name, without a
 *   program, template or package, without `{url}` in a program's arguments or `{url}`/`{url_raw}` in a URL template,
 *   or whose package isn't a package name. Deleting the chosen one makes Tortuga the player again.
 *
 * Call everything on the main thread; [release] ends it.
 */
class PlayerSettingsStore internal constructor(
    private val catalog: PlayerAppCatalog,
    private val deviceSettings: DeviceSettingsRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** What custom players are on this platform; null where none can be added. */
    private val customKind = catalog.launcher.customPlayers
    private var pageOpen = false
    private var formOpen = false
    private var formError: String? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<PlayerSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch { merge(catalog.players, catalog.settings, catalog.detecting, catalog.known).collect { publish() } }
    }

    /** Opens the page ([PlayerSettingsUiState.pageOpen]) and looks for the players on this device again. */
    fun openPage() {
        pageOpen = true
        catalog.detect()
        publish()
    }

    /** Closes the page (and the Add custom player form). */
    fun closePage() {
        pageOpen = false
        formOpen = false
        formError = null
        publish()
    }

    /** Plays everything on this device in the player [id] ([PlayerChoiceRow.id]) from now on. */
    fun select(id: String) {
        val name = if (id == PlayerApp.TORTUGA_ID) null else catalog.find(id)?.name ?: return
        scope.launch {
            deviceSettings.set(DeviceSetting.Player, id)
            deviceSettings.set(DeviceSetting.PlayerName, name)
        }
    }

    /** Opens the Add custom player form ([PlayerSettingsUiState.customForm]). */
    fun openAddCustom() {
        if (customKind == null) return
        formOpen = true
        formError = null
        publish()
    }

    /** Closes the form without adding. */
    fun closeAddCustom() {
        formOpen = false
        formError = null
        publish()
    }

    /**
     * Adds the custom player [name] with [command] and [arguments] ([CustomPlayerForm]; [arguments] is ignored where the
     * form has none) and closes the form; when they don't do, the form stays with [CustomPlayerForm.error].
     */
    fun saveCustom(name: String, command: String, arguments: String) {
        val kind = customKind ?: return
        val player = CustomPlayer(id = "", name = name.trim(), command = command.trim(), arguments = if (kind == CustomPlayerKind.COMMAND) arguments.trim() else "")
        val error = refusal(kind, player)
        if (error != null) {
            formError = error
            return publish()
        }
        formOpen = false
        formError = null
        publish()
        scope.launch {
            deviceSettings.update(DeviceSetting.CustomPlayers) { stored ->
                val id = generateSequence(1) { it + 1 }.map(Int::toString).first { candidate -> stored.none { it.id == candidate } }
                stored + player.copy(id = id)
            }
        }
    }

    /** Deletes the custom player [id] ([PlayerChoiceRow.id]); when it was chosen, Tortuga plays again. */
    fun deleteCustom(id: String) {
        if (!id.startsWith(PlayerApp.CUSTOM_PREFIX)) return
        val customId = id.removePrefix(PlayerApp.CUSTOM_PREFIX)
        scope.launch {
            deviceSettings.update(DeviceSetting.CustomPlayers) { stored -> stored.filterNot { it.id == customId } }
            if (deviceSettings.value(DeviceSetting.Player) == id) {
                deviceSettings.reset(DeviceSetting.Player)
                deviceSettings.reset(DeviceSetting.PlayerName)
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): PlayerSettingsUiState {
        val text = T.playerApps.settings
        val settings = catalog.settings.value
        val chosen = settings?.player
        val players = catalog.players.value
        val found = chosen?.let(catalog::find)
        val chosenName = found?.name ?: chosen?.let { settings.playerName ?: it }
        // Only once the players were looked for: until then it may just not be found yet.
        val missing = chosen != null && found == null && catalog.known.value
        val tortuga = PlayerChoiceRow(PlayerApp.TORTUGA_ID, T.playerApps.tortuga, detail = null, selected = chosen == null, missing = false, hasIcon = false, deleteText = null)
        val detected = players.filter { it.kind != PlayerAppKind.CUSTOM }.map { app ->
            PlayerChoiceRow(app.id, app.name, detail(app), selected = app.id == chosen, missing = false, hasIcon = app.drawsIcon(), deleteText = null)
        }
        val gone = if (missing && chosenName != null) {
            listOf(PlayerChoiceRow(chosen, text.notFound(isolate(chosenName)), detail = null, selected = true, missing = true, hasIcon = false, deleteText = null))
        } else {
            emptyList()
        }
        val custom = if (customKind == null) {
            emptyList()
        } else {
            players.filter { it.kind == PlayerAppKind.CUSTOM }.map { app ->
                PlayerChoiceRow(app.id, app.name, app.custom?.let(::customDetail), selected = app.id == chosen, missing = false, hasIcon = false, deleteText = text.deleteNamed(isolate(app.name)))
            }
        }
        return PlayerSettingsUiState(
            row = PlayerSettingRow(
                title = text.player,
                valueLabel = when {
                    chosenName == null -> T.playerApps.tortuga
                    missing -> text.notFound(isolate(chosenName))
                    else -> chosenName
                },
                note = text.deviceOnly,
            ),
            pageOpen = pageOpen,
            title = text.player,
            note = text.note,
            detecting = catalog.detecting.value,
            players = listOf(tortuga) + detected + gone,
            customHeader = text.customHeader.takeIf { customKind != null },
            customPlayers = custom,
            addCustomText = text.addCustom.takeIf { customKind != null },
            customForm = customKind?.takeIf { formOpen }?.let(::form),
        )
    }

    /** What [app] does with a play, in one line. */
    private fun detail(app: PlayerApp): String {
        val text = T.playerApps.settings
        val capabilities = app.capabilities
        return when {
            app.kind == PlayerAppKind.CHOOSER -> text.chooserDetail
            capabilities.progress == PlayerAppProgress.LIVE -> text.followsProgress
            capabilities.progress == PlayerAppProgress.ON_RETURN -> if (capabilities.startPosition) text.savesOnReturn else text.savesOnReturnFromStart
            else -> if (capabilities.startPosition) text.asksAfter else text.asksAfterFromStart
        }
    }

    /** A custom player's line: its command and arguments, its template or its package. */
    private fun customDetail(player: CustomPlayer): String =
        listOf(player.command, player.arguments).filter { it.isNotBlank() }.joinToString(" ")

    /** Android draws a detected app's own icon beside it. */
    private fun PlayerApp.drawsIcon(): Boolean = kind == PlayerAppKind.DETECTED && customKind == CustomPlayerKind.PACKAGE

    private fun form(kind: CustomPlayerKind): CustomPlayerForm {
        val text = T.playerApps.settings.form
        val command = kind == CustomPlayerKind.COMMAND
        return CustomPlayerForm(
            title = text.title,
            kind = kind,
            nameLabel = text.name,
            nameHint = text.nameHint,
            commandLabel = when (kind) {
                CustomPlayerKind.COMMAND -> text.program
                CustomPlayerKind.URL_TEMPLATE -> text.urlTemplate
                CustomPlayerKind.PACKAGE -> text.packageName
            },
            commandHint = when (kind) {
                CustomPlayerKind.COMMAND -> text.programHint
                CustomPlayerKind.URL_TEMPLATE -> text.urlTemplateHint
                CustomPlayerKind.PACKAGE -> text.packageNameHint
            },
            argumentsLabel = text.arguments.takeIf { command },
            argumentsHint = text.argumentsHint.takeIf { command },
            argumentsDefault = if (command) PlayerAppTemplates.URL else "",
            placeholders = when (kind) {
                CustomPlayerKind.COMMAND -> text.programPlaceholders
                CustomPlayerKind.URL_TEMPLATE -> text.urlTemplatePlaceholders
                CustomPlayerKind.PACKAGE -> null
            },
            error = formError,
            saveText = text.save,
            cancelText = text.cancel,
        )
    }

    private companion object {
        /** An Android package name: two or more dot-separated parts, each a letter then letters, digits or `_`. */
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /** Why [player] can't be added as a [kind] player, or null when it can. */
        fun refusal(kind: CustomPlayerKind, player: CustomPlayer): String? {
            val text = T.playerApps.settings.form
            if (player.name.isEmpty()) return text.nameMissing
            return when (kind) {
                CustomPlayerKind.COMMAND -> when {
                    player.command.isEmpty() -> text.programMissing
                    PlayerAppTemplates.URL !in player.arguments -> text.argumentsNeedUrl
                    else -> null
                }
                CustomPlayerKind.URL_TEMPLATE -> when {
                    player.command.isEmpty() -> text.urlTemplateMissing
                    PlayerAppTemplates.URL !in player.command && PlayerAppTemplates.URL_RAW !in player.command -> text.urlTemplateNeedsUrl
                    else -> null
                }
                CustomPlayerKind.PACKAGE -> when {
                    player.command.isEmpty() -> text.packageNameMissing
                    !PACKAGE_NAME.matches(player.command) -> text.packageNameInvalid
                    else -> null
                }
            }
        }
    }
}
