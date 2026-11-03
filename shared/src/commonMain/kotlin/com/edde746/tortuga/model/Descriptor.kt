package com.edde746.tortuga.model

import kotlinx.serialization.Serializable

/**
 * An installed (or listed) addon: its manifest and the transport URL it was fetched from (stremio-core
 * `Descriptor`), checked where the addon was read ([TransportUrl.checked]). The transport URL is a secret: configured
 * addons embed API keys in it.
 */
data class Descriptor(
    val manifest: Manifest,
    val transport: TransportUrl,
    val flags: DescriptorFlags = DescriptorFlags(),
) {
    /** [transport] as text: the addon's key. */
    val transportUrl: String get() = transport.value
}

/** Set only for the built-in addons; a protected addon can't be removed or replaced. */
@Serializable
data class DescriptorFlags(
    val official: Boolean = false,
    val protected: Boolean = false,
)
