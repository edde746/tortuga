package com.edde746.tortuga.desktop.secrets

import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.secrets.SecretStoreContract

/** The contract against this OS's store: Windows Credential Manager or libsecret (`:app-desktop:secretStoreTest`). */
class DesktopSecretStoreTest : SecretStoreContract() {
    override fun createStore(): SecretStore = desktopSecretStore()
}
