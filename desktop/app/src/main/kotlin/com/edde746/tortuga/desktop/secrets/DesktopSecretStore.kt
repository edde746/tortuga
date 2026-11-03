package com.edde746.tortuga.desktop.secrets

import com.edde746.tortuga.secrets.SecretStore

/**
 * The [SecretStore] of the OS the app runs on: Windows Credential Manager ([WindowsCredentialStore]) or the freedesktop
 * Secret Service through libsecret ([LibsecretStore]). Nothing native is loaded until the first call.
 */
internal fun desktopSecretStore(): SecretStore {
    val os = System.getProperty("os.name")
    return when {
        os.startsWith("Windows") -> WindowsCredentialStore()
        os.startsWith("Linux") -> LibsecretStore()
        else -> throw UnsupportedOperationException("No secret store for $os: the desktop app supports Windows and Linux")
    }
}
