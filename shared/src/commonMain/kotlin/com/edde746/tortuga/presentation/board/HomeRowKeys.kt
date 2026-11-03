package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog

// The keys of Home's rows in the profile's layout (data/settings/HomeLayout): the same on every launch and device, so
// a row keeps its place and visibility. They hold an addon's transport URL, which may carry its configuration's keys:
// they go to the stored setting (synced like the addon list itself), never into a UI state or the logs.

/**
 * Each planned catalog's key: `addon|<transport URL>|<type>|<id>`, then `|<name>=<value>&…` with an extra, and `|#2`,
 * `|#3` for a request planned more than once.
 */
internal fun homeRowKeys(plan: List<PlannedRequest>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return plan.map { planned ->
        val path = planned.request.path
        val extra = if (path.extra.isEmpty()) "" else "|" + path.extra.joinToString("&") { "${it.name}=${it.value}" }
        val base = "$ADDON_PREFIX${planned.request.base.value}|${path.type}|${path.id}$extra"
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base|#$count"
    }
}

/** A tracker list's key: `tracker|<service>|<list id>`. */
internal fun trackerHomeKey(service: TrackerService, catalog: TrackerCatalog): String = "$TRACKER_PREFIX${service.id}|${catalog.id}"

/** The key of a tracker whose lists couldn't be read; no layout names it, so its row takes its default place. */
internal fun trackerListsHomeKey(service: TrackerService): String = "$TRACKER_PREFIX${service.id}"

/** The service a [trackerHomeKey] names; null for any other key. */
internal fun trackerOfHomeKey(key: String): TrackerService? =
    if (key.startsWith(TRACKER_PREFIX)) TrackerService.fromId(key.removePrefix(TRACKER_PREFIX).substringBefore('|')) else null

/** What every key of the addon at [transportUrl]'s rows starts with. */
internal fun addonHomeKeyPrefix(transportUrl: String): String = "$ADDON_PREFIX$transportUrl|"

/** A collection's key: `collection|<collection id>`. */
internal fun collectionHomeKey(id: String): String = "$COLLECTION_PREFIX$id"

private const val ADDON_PREFIX = "addon|"
private const val TRACKER_PREFIX = "tracker|"
private const val COLLECTION_PREFIX = "collection|"
