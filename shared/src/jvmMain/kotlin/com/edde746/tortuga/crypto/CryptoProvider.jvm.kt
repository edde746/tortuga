package com.edde746.tortuga.crypto

import dev.whyoleg.cryptography.CryptographyProvider

internal actual fun platformCryptoProvider(): CryptographyProvider = CryptographyProvider.Default
