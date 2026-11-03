package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.strings.T

/**
 * What Home, Discover and Search show in place of their catalogs while the device is offline
 * ([com.edde746.tortuga.app.AppGraph.network]): [title] and [text], and [actionText], the label of the action that
 * opens Downloads (the store's `openDownloads`), where this device has downloads; null where it has none (Apple TV),
 * which shows the text alone.
 */
data class OfflineState(val title: String, val text: String, val actionText: String?) {
    internal companion object {
        fun of(offersDownloads: Boolean): OfflineState = OfflineState(
            title = T.offline.title,
            text = if (offersDownloads) T.offline.text else T.offline.textNoDownloads,
            actionText = if (offersDownloads) T.offline.openDownloads else null,
        )
    }
}
