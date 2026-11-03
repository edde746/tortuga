package com.edde746.tortuga.desktop

import com.edde746.tortuga.presentation.player.MediaControlsHost

/** The OS media controls on a desktop (SMTC on Windows, MPRIS on Linux), released when the app closes. */
internal interface DesktopMediaControls : MediaControlsHost, AutoCloseable
