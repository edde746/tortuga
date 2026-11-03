package com.edde746.tortuga.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.edde746.tortuga.app.AppPlatform
import com.edde746.tortuga.playback.PlayerEngine

/**
 * What the Compose app needs from the platform it runs on (Android, desktop), beyond what the shared app sessions need
 * ([AppPlatform]): the video, picture in picture, the share sheet, a folder picker and the file dialogs. Called on the
 * main thread.
 */
interface TortugaPlatform : AppPlatform {
    /** The video of [engine] (a playback's, [AppPlatform.createPlayback]), filling [modifier]'s bounds under the controls. */
    @Composable
    fun VideoSurface(engine: PlayerEngine, modifier: Modifier)

    /** The app shows in a picture-in-picture window (Android): the player shows only its video. Compose state. */
    val inPictureInPicture: Boolean get() = false

    /**
     * The app's window is on screen: false while it is minimized (desktop). The player lets the display sleep meanwhile
     * (`PlayerStore.onVisibleChanged`). Android needs none: its keep-screen-on flag acts only while the window shows, and
     * the player pauses when its surface goes. Compose state.
     */
    val windowVisible: Boolean get() = true

    /**
     * An assistive technology that moves through the controls one at a time runs (Android: TalkBack's touch
     * exploration): the player keeps its controls up meanwhile (`PlayerStore.onAssistiveTechnologyChanged`). Compose
     * state.
     */
    val assistiveTechnology: Boolean get() = false

    /** The platform has a share sheet ([share]): Android phones and tablets; not TV or desktop. */
    val canShare: Boolean get() = false

    /** Opens the platform's share sheet with [text] (a watch-together invite); nothing where [canShare] is false. */
    fun share(text: String) {}

    /**
     * Opens the platform's folder picker titled [title], at [initial] (an absolute path) where it can, and hands the
     * picked folder's absolute path to [onChosen]; nothing when the user cancels. Desktop only (Settings → Downloads'
     * folder: `DownloadSettingsUiState.folder` is null elsewhere), a no-op on the others.
     */
    fun chooseFolder(title: String, initial: String?, onChosen: (String) -> Unit) {}

    /**
     * Shows the system's save dialog for a JSON file, [fileName] proposed, writes [text] into the chosen file as UTF-8
     * off the main thread, then reports how it went to [done] on the main thread. Only where [hasFileDialogs] is true
     * (Settings → Export and import). Never logs the file's contents or where it went.
     */
    fun saveFile(fileName: String, text: String, done: (SaveResult) -> Unit)

    /**
     * Shows the system's open dialog for any file (one saved under another type is still offered), reads the chosen
     * file off the main thread and decodes it strictly as UTF-8, then hands its display name and text to [opened] on the
     * main thread, or calls [failed] when it can't be read or isn't UTF-8 text; nothing when the user cancels. Only
     * where [hasFileDialogs] is true (Settings → Export and import, the player's shader import). Never logs the file's
     * contents or where it is.
     */
    fun openFile(opened: (name: String, text: String) -> Unit, failed: () -> Unit)

    /**
     * The icon of the player app [playerId] (`PlayerApp.id`) in [modifier]'s bounds, for the Player page and "Play
     * with…" rows whose `PlayerChoiceRow.hasIcon` is true: Android draws the app's launcher icon; elsewhere nothing.
     */
    @Composable
    fun PlayerAppIcon(playerId: String, modifier: Modifier) {}
}

/** How a save dialog ([TortugaPlatform.saveFile]) ended. */
enum class SaveResult {
    SAVED,

    /** A file was chosen but couldn't be written. */
    FAILED,

    /** The user closed the dialog without saving. */
    CANCELLED,
}

/** The platform of the app on screen, provided by the app's frame. */
val LocalTortugaPlatform = staticCompositionLocalOf<TortugaPlatform> { error("no TortugaPlatform provided") }
