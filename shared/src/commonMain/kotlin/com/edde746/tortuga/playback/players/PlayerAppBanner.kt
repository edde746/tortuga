package com.edde746.tortuga.playback.players

/**
 * A hand-off to a player app while it is under way, or after it failed (`AppSessionState.playerApp`): one line over
 * the screen the user is on, the Streams sheet included.
 *
 * @param text "Opening in VLC…" while the stream resolves and the player starts ([busy]); after a failure, why: "VLC
 *   isn't installed on this device.", "VLC didn't start.", or the stream's failure as the player words it.
 * @param busy the hand-off is under way: a spinner; [dismissText] ("Cancel") stops it.
 * @param playInTortugaText "Play in Tortuga" after the player app couldn't start: `AppSession.playInTortuga` plays the
 *   same video in Tortuga's player; null otherwise.
 * @param dismissText "Cancel" while [busy], else "Dismiss": `AppSession.dismissPlayerApp`.
 */
data class PlayerAppBanner(
    val text: String,
    val busy: Boolean,
    val playInTortugaText: String?,
    val dismissText: String,
)
