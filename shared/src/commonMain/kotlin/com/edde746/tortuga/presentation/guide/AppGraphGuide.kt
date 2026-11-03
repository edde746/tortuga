package com.edde746.tortuga.presentation.guide

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import com.edde746.tortuga.presentation.calendar.localUtcOffsetSeconds

/** The TV guide of [profileId]'s installed addons, in the device's time zone. */
fun AppGraph.guideStore(profileId: String, host: GuideHost): GuideStore =
    GuideStore(installedAddonsFlow(profileId), aggregator, host, scope, { clock.now() }, ::localUtcOffsetSeconds)
