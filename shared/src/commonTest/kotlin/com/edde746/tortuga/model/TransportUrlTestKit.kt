package com.edde746.tortuga.model

/** This text as a [TransportUrl]; fails for one that isn't. */
internal fun String.transportUrl(): TransportUrl = checkNotNull(TransportUrl.parse(this)) { "not a transport URL: $this" }
