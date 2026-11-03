package com.edde746.tortuga.desktop.linux

import java.io.File

/**
 * The app runs inside its Flatpak's sandbox (`com.edde746.tortuga.json` in `desktop/app/packaging/linux/`): Flatpak puts
 * `/.flatpak-info` at the root of every sandbox it starts. There the home folder lives in memory and only what the
 * manifest shares persists, so downloads go to the shared Videos folder ([com.edde746.tortuga.desktop.DesktopDownloads]),
 * the file dialogs are the desktop portal's ([FileChooserPortal]), and player apps are off (the sandbox sees no host
 * programs).
 */
internal val inFlatpak: Boolean = File("/.flatpak-info").exists()
