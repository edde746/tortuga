package com.edde746.tortuga.app

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.detail.TitleLink
import com.edde746.tortuga.presentation.homescreen.HomeScreenLink
import com.edde746.tortuga.sync.account.DeviceLinkQr
import com.edde746.tortuga.watch.relay.WatchInvite

/** A link the OS handed the app, parsed once ([parse]) where it comes in ([RootSession.openUrl]). */
sealed interface IncomingLink {
    /** A watch-together invite (`tortuga://watch?…`). */
    data class Invite(val invite: WatchInvite) : IncomingLink

    /** A device link's QR code (`tortuga://link?…`), scanned with a camera. */
    data class DeviceLink(val qr: DeviceLinkQr) : IncomingLink

    /** An item of a profile's on the TV's home screen. */
    data class HomeScreen(val item: HomeScreenLink) : IncomingLink

    /** A `stremio:` link, or a title's link ([TitleLink]) as the `stremio:///detail` link it stands for. */
    data class Stremio(val link: DeepLink) : IncomingLink

    companion object {
        /** The link [url] is; null for one this app doesn't open. */
        fun parse(url: String): IncomingLink? =
            WatchInvite.parseLink(url)?.let(::Invite)
                ?: DeviceLinkQr.parse(url)?.let(::DeviceLink)
                ?: HomeScreenLink.parse(url)?.let(::HomeScreen)
                ?: DeepLink.parse(url)?.let(::Stremio)
                ?: TitleLink.parse(url)?.let { Stremio(DeepLink.Detail(it.type, it.id)) }
    }
}
