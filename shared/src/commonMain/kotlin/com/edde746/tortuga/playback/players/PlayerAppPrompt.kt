package com.edde746.tortuga.playback.players

import kotlinx.serialization.Serializable

/**
 * A "Finished watching?" prompt (`DeviceSetting.PlayerAppPrompts`): [videoId] of the title [metaId] went to the player
 * app [player], which reports nothing back (or came back without a position), so Detail asks [profileId] whether it
 * was watched to the end. One per profile and title; a later playback of the title replaces it.
 *
 * @param player the player's name as the prompt shows it ("VLC").
 * @param label what was handed off, as the prompt names it: "S1E3 · Pilot", "The Shawshank Redemption".
 */
@Serializable
data class PlayerAppPrompt(
    val profileId: String,
    val metaId: String,
    val videoId: String,
    val player: String,
    val label: String,
)
