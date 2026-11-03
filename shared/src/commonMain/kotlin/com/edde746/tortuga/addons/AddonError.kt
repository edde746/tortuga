package com.edde746.tortuga.addons

import com.edde746.tortuga.strings.T

/**
 * Why an addon request, an install or a registry change failed. Carried by [AddonException] and by
 * [Loadable.Error]. Detail texts are redacted; only [ConfigurationRequired] holds a URL (the page to open).
 */
sealed interface AddonError {
    /** Not an HTTP(S) URL ending in `/manifest.json` (legacy `/stremio/v1` addons included). */
    data object InvalidTransportUrl : AddonError

    /** No response within the per-addon timeout (or a connect/socket timeout). */
    data object Timeout : AddonError

    /** The request didn't complete: DNS, TLS, connection reset. [detail] is redacted. */
    data class Network(val detail: String) : AddonError

    /** A non-2xx status. */
    data class Http(val status: Int) : AddonError

    /** The body isn't a valid response of the addon protocol (not JSON, no or several payload keys). */
    data class InvalidResponse(val detail: String) : AddonError

    /** A valid response of another kind than requested (stremio-core `UnexpectedResponse`). */
    data class UnexpectedResponse(val expected: String, val actual: String) : AddonError

    /** A list response with no items (stremio-core `EmptyContent`). */
    data object EmptyContent : AddonError

    /** The manifest doesn't decode or lacks required fields. [detail] is redacted. */
    data class InvalidManifest(val detail: String) : AddonError

    /** The addon must be configured on its [configureUrl] page before it can be installed. */
    data class ConfigurationRequired(val configureUrl: String) : AddonError

    data object AlreadyInstalled : AddonError
    data object NotInstalled : AddonError

    /** A built-in addon that can't be removed or replaced. */
    data object Protected : AddonError
}

/** The user-visible description of this error. */
val AddonError.message: String
    get() = when (this) {
        AddonError.InvalidTransportUrl -> T.addons.error.invalidTransportUrl
        AddonError.Timeout -> T.addons.error.timeout
        is AddonError.Network -> T.addons.error.network
        is AddonError.Http -> T.addons.error.http(status.toString())
        is AddonError.InvalidResponse -> T.addons.error.invalidResponse
        is AddonError.UnexpectedResponse -> T.addons.error.unexpectedResponse
        AddonError.EmptyContent -> T.addons.error.emptyContent
        is AddonError.InvalidManifest -> T.addons.error.invalidManifest
        is AddonError.ConfigurationRequired -> T.addons.error.configurationRequired
        AddonError.AlreadyInstalled -> T.addons.error.alreadyInstalled
        AddonError.NotInstalled -> T.addons.error.notInstalled
        AddonError.Protected -> T.addons.error.protectedAddon
    }

class AddonException(val error: AddonError, cause: Throwable? = null) : Exception(error.toString(), cause)
