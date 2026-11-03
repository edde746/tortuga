package com.edde746.tortuga.watch.media

import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.playback.session.AutoPickRule
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamBadges
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlin.math.abs

/**
 * The order in which a guest opens its candidates for the host's file ([FileMatcher]), over its addons' answers.
 *
 * Candidates are the playable streams; a stream its text marks as not ready
 * ([StreamBadges.cached] false) is never one, since opening it makes the addon's service fetch the file (it stays in the
 * stream list for a manual pick). They go, the earlier rule deciding:
 * 1. the same video hash as the host's (`behaviorHints.videoHash`);
 * 2. the same file name (`behaviorHints.filename`, ignoring case) or the same exact size (`behaviorHints.videoSize`) as
 *    the host's;
 * 3. by how close their size is to the host's, when both are known (`behaviorHints.videoSize`, else the size the
 *    stream's text names, rounded), before the ones whose size isn't known;
 * 4. the guest's own order: auto-play's pick with the profile's mode, filters and sort ([AutoPickRule]), then the
 *    stream the video last played from (stream history) as its addon offers it now, then the streams that pass the
 *    filters, then the rest; each group in the list's order (addons as installed, then each one's order).
 */
internal object CandidateRanking {
    fun rank(
        states: List<AddonLoadable<List<Stream>>>,
        host: FileFingerprint,
        filter: StreamFilter,
        sort: StreamSort,
        mode: AutoPlayMode,
        remembered: StreamChoice?,
    ): List<StreamChoice> {
        val autoPick = if (mode == AutoPlayMode.OFF) {
            null
        } else {
            val decision = AutoPickRule.decide(states, filter, remembered.takeIf { mode == AutoPlayMode.REUSE_LAST_LINK }, sort = sort)
            (decision as? AutoPickRule.Decision.Picked)?.choice?.stream
        }
        val lastLink = remembered?.let { offered(states, it) }
        val candidates = mutableListOf<Candidate>()
        for (addonState in states) {
            val streams = (addonState.state as? Loadable.Ready)?.content ?: continue
            for (stream in streams) {
                if (stream.playability !is StreamPlayability.Playable) continue
                val badges = StreamBadgeParser.parse(stream)
                if (badges.cached == false) continue
                val guestRank = when {
                    stream === autoPick -> 0
                    stream === lastLink -> 1
                    filter.accepts(stream, badges) -> 2
                    else -> 3
                }
                val choice = StreamChoice(stream, addonState.addon.transportUrl, addonState.addon.manifest.name)
                candidates += Candidate(choice, stream.behaviorHints.videoSize, badges.sizeBytes, guestRank)
            }
        }
        return candidates.sortedWith(order(host)).map { it.choice }
    }

    /** [exactSize] the size the addon states; [sizeBytes] that, else the rounded one the stream's text names. */
    private class Candidate(val choice: StreamChoice, val exactSize: Long?, textSize: Long?, val guestRank: Int) {
        val sizeBytes: Long? = exactSize ?: textSize
    }

    private fun order(host: FileFingerprint): Comparator<Candidate> = compareBy<Candidate>(
        { !sameHash(it, host) },
        { !sameNameOrSize(it, host) },
        { candidate -> host.sizeBytes?.let { size -> candidate.sizeBytes?.let { abs(it - size) } } ?: Long.MAX_VALUE },
        { it.guestRank },
    )

    private fun sameHash(candidate: Candidate, host: FileFingerprint): Boolean {
        val hash = host.videoHash ?: return false
        return candidate.choice.stream.behaviorHints.videoHash.equals(hash, ignoreCase = true)
    }

    private fun sameNameOrSize(candidate: Candidate, host: FileFingerprint): Boolean {
        val filename = candidate.choice.stream.behaviorHints.filename
        val sameName = host.filename != null && filename.equals(host.filename, ignoreCase = true)
        return sameName || (host.sizeBytes != null && candidate.exactSize == host.sizeBytes)
    }

    /** [remembered] as its addon offers it among [states] now ([StreamChoice.indexOfRemembered]), or null. */
    private fun offered(states: List<AddonLoadable<List<Stream>>>, remembered: StreamChoice): Stream? {
        val addonState = states.firstOrNull { it.addon.transportUrl == remembered.addonUrl } ?: return null
        val streams = (addonState.state as? Loadable.Ready)?.content ?: return null
        return streams.getOrNull(StreamChoice.indexOfRemembered(streams, addonState.addon.transportUrl, remembered))
    }
}
