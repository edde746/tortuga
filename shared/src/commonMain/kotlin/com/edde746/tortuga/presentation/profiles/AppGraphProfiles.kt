package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.app.createProfile
import com.edde746.tortuga.presentation.account.AccountGate

/** The profile picker ([com.edde746.tortuga.presentation.account.AccountDestination.Profiles]). */
fun AppGraph.profilePickerStore(gate: AccountGate, host: ProfilePickerHost): ProfilePickerStore =
    ProfilePickerStore(data.profiles, gate, host, scope)

/**
 * The profile editor: a new profile for a null [profileId] (created through [AppGraph.createProfile], so it gets the
 * default addons), else that profile. Check the profile's PIN before opening it (the picker does).
 */
fun AppGraph.profileEditorStore(profileId: String?, host: ProfileEditorHost): ProfileEditorStore = ProfileEditorStore(
    profileId,
    data.profiles,
    data.settings,
    { name, avatar, kids, homeCatalogs -> createProfile(name, avatar, kids, homeCatalogs) },
    { addonRegistry(it).current() },
    { ageRatings?.prefetch() },
    host,
    scope,
)
