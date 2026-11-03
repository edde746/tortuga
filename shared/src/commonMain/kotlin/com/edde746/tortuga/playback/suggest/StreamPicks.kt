package com.edde746.tortuga.playback.suggest

import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.presentation.detail.DetailText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Records the streams [profileId] picks by hand on this device and what came of each ([StreamPickRepository]), for
 * stream suggestions: a pick in the Streams list ([listPick]) and the player's next stream ([playerPick]), each with
 * the streams shown around it and where it was made ([context]: the kind of device, the network, the screen and the
 * decoders, as they are at the pick). Only while the profile's suggestions are on ([settings]'
 * `ProfileSetting.StreamSuggestions`, followed as it changes): while off no pick is recorded, and what comes of a pick
 * recorded before still is. A kids profile records as any other. Writes run one at a time in call order in [scope];
 * one that fails is logged and the next one runs.
 *
 * Keeps the [StreamScorer] learnt from the picks of every device of the profile ([scorer]), so a list opening only
 * scores its streams. It is learnt on [learnDispatcher] when a list first asks, and again, in the background, when a
 * list asks after the picks changed (a pick or outcome here, or picks pulled from the profile's other devices); that
 * list gets the one learnt before, never waiting, and nothing is learnt while a stream starts playing. It lives in
 * memory: the picks are what is stored.
 *
 * Call on the main thread.
 */
class StreamPicks internal constructor(
    private val profileId: String,
    private val repository: StreamPickRepository,
    private val settings: SettingsRepository,
    private val readContext: () -> PickContext,
    private val scope: CoroutineScope,
    private val learnDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * A scorer (null: the picks suggest nothing), what it was learnt for (the kind of device, the profile's languages
     * and sort) and where the picks were at ([StreamPickRepository.changeMark]).
     */
    private class Learnt(val settings: List<Any>, val mark: String, val scorer: StreamScorer?)

    private var learnt: Learnt? = null

    /** The profile's suggestions are on: picks are recorded. */
    private var recording = false

    /** The learning under way in the background, one at a time. */
    private var learning: Job? = null

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, e) { "recording a stream pick failed" }
                }
            }
        }
        scope.launch {
            settings.observe(profileId).map { it.streamSuggestions }.collect { recording = it }
        }
    }

    /** The profile's picks on every kind of device, oldest first. */
    suspend fun picks(): List<StreamPick> = repository.picks(profileId)

    /** The kind of device, the network, the screen and the decoders as they are now. */
    fun context(): PickContext = readContext()

    /**
     * The scorer for this kind of device of the profile's picks, for a profile wanting [languages] and sorting by
     * [sortKeys] (its prior, [StreamScorer.prior]); null while the picks suggest nothing. With a scorer learnt for the
     * same settings it is that one, learnt again in the background when the picks have changed since.
     */
    suspend fun scorer(languages: LanguagePrefs, sortKeys: List<StreamSortKey>): StreamScorer? {
        val settings = listOf(context().deviceClass, languages, sortKeys)
        val mark = repository.changeMark(profileId)
        val last = learnt
        if (last == null || last.settings != settings) return learn(settings, mark, languages, sortKeys, from = last?.scorer)
        if (last.mark != mark && learning?.isActive != true) {
            learning = scope.launch { learn(settings, mark, languages, sortKeys, from = last.scorer) }
        }
        return last.scorer
    }

    /** Learns the scorer for [settings] from the picks as they are at [mark], starting from the weights of [from]. */
    private suspend fun learn(settings: List<Any>, mark: String, languages: LanguagePrefs, sortKeys: List<StreamSortKey>, from: StreamScorer?): StreamScorer? {
        val history = picks()
        val deviceClass = context().deviceClass
        val scorer = withContext(learnDispatcher) {
            StreamScorer.train(history, languages, deviceClass, prior = StreamScorer.prior(sortKeys, languages), from = from)
        }
        learnt = Learnt(settings, mark, scorer)
        return scorer
    }

    /**
     * [chosen] of [shown] (the list's playable rows the filters let through, in its order) was picked for [videoId] of
     * [meta]; returns the pick's id ([com.edde746.tortuga.playback.session.PlayRequest.pickId]), null while suggestions
     * are off (nothing recorded).
     */
    fun listPick(meta: MetaItem, videoId: String, shown: List<PickedStream>, chosen: Int): String? =
        record(meta, videoId, PickSource.LIST, shown, chosen)

    /**
     * The player went on from [from] to [to], the next of its alternatives ([rest] the ones after it): a pick of [to]
     * over [from]; its id, null while suggestions are off (nothing recorded).
     */
    fun playerPick(meta: MetaItem, videoId: String, from: StreamChoice, to: StreamChoice, rest: List<StreamChoice>): String? =
        if (recording) record(meta, videoId, PickSource.PLAYER, (listOf(from, to) + rest.take(BELOW)).map(::picked), chosen = 1) else null

    /** What came of the pick [pickId]. */
    fun outcome(pickId: String, outcome: PickOutcome) {
        writes.trySend { repository.setOutcome(pickId, outcome) }
    }

    /** Keeps the [ABOVE] candidates before [chosen] and the [BELOW] after it: what a pick says about its list. */
    private fun record(meta: MetaItem, videoId: String, source: PickSource, shown: List<PickedStream>, chosen: Int): String? {
        if (!recording) return null
        val id = Uuid.random().toString()
        val start = (chosen - ABOVE).coerceAtLeast(0)
        val end = (chosen + BELOW + 1).coerceAtMost(shown.size)
        val pick = StreamPick(
            id = id,
            metaId = meta.id,
            videoId = videoId,
            runtimeSeconds = DetailText.runtimeMinutes(meta.preview.runtime)?.let { it * 60L },
            pickedAt = 0,
            context = context(),
            source = source,
            candidates = shown.subList(start, end),
            chosen = chosen - start,
            outcome = null,
            firstRow = start,
        )
        writes.trySend { repository.record(profileId, pick) }
        return id
    }

    private fun picked(choice: StreamChoice): PickedStream {
        val badges = StreamBadgeParser.parse(choice.stream)
        return PickedStream.of(badges, StreamFilter.sizeOf(choice.stream, badges))
    }

    companion object {
        private const val TAG = "streams"

        /** Candidates kept before the picked one: the ones passed over to reach it. */
        const val ABOVE: Int = 25

        /** Candidates kept after it. */
        const val BELOW: Int = 10
    }
}
