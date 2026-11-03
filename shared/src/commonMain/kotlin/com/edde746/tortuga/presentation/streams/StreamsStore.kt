package com.edde746.tortuga.presentation.streams

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.playback.streams.StreamBadge
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamBadges
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import com.edde746.tortuga.playback.streams.StreamText
import com.edde746.tortuga.playback.streams.display
import com.edde746.tortuga.playback.streams.notShownIn
import com.edde746.tortuga.playback.suggest.LanguagePrefs
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.playback.suggest.StreamScorer
import com.edde746.tortuga.presentation.detail.DetailText
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.presentation.players.playWithMenu
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Streams screen's store: the streams of [videoId] of [meta] from every installed stream
 * addon, grouped by addon in the installed order, each group loading, failing or filling on its own (the
 * aggregator's per-addon states). The streams [meta] carries for the video (its `streams`, a YouTube channel video's
 * own) come first, as a group named after the addon that sent [meta] (stremio-core's `meta_streams`). A group is
 * listed while it has something to show ([StreamGroup]): an addon that answered with nothing leaves no heading alone.
 *
 * - Rows keep the addon's order, unless the profile sorts the lists ([StreamSort], from [settings]; the sort menu,
 *   [toggleSort], sets it): then every answered addon's rows are one list in that order, ties keeping the addons'
 *   order, and the addons still answering or failed follow with their headings. Their text is the addon's, tidied
 *   ([StreamText]: the padding addons write for Stremio's own layout goes, a name is one line); badges come from it
 *   ([StreamBadgeParser]), less the ones the text already shows. The filters and the sort go by every badge read.
 * - Only streams this app can act on are listed: HTTP streams play, `externalUrl`/YouTube streams open outside.
 *   Streams that aren't direct links are hidden, and counted in a note where [policy] allows it.
 * - The profile's stream filters ([StreamFilter], from [settings]) hide streams too: the list as a whole says how many,
 *   and each group too when more than one hides some (one group's count would only repeat the list's) or when the
 *   filters hide every stream it lists (the count is all it shows), and [toggleFiltered] shows them for this list
 *   (noted as filtered), or hides them again. An addon's answer waits for the filters, so no row shows and then
 *   vanishes. Its text and badges are read once, off the main thread, as it arrives; its rows are built once per
 *   filter, so a long aggregator list stays cheap to show, hide and update while the other addons answer.
 * - The stream this video last played from (stream history, synced) is marked.
 * - A pick that plays is recorded with the playable rows shown around it ([picks]). While the profile's suggestions are
 *   on (`ProfileSetting.StreamSuggestions`, followed as it changes), once every addon answered, the stream the
 *   profile's picks on this kind of device suggest ([StreamScorer]) is marked with why ([StreamRow.suggestion]), where
 *   the sort put it: only among the playable rows the filters let through. Never in [downloadMode].
 * - [pick] hands a playable stream to [host] as a [PlayRequest], with the other playable streams listed as
 *   alternatives: the ones after it first, then the ones before. With [fromStart] (Play from the beginning) it plays
 *   from 0 ([PlayRequest.startPositionSeconds]).
 * - [notice], when the list opened in auto-play's place (it found nothing, or its pick didn't start), heads the list.
 * - When the installed addons can't be read, the list says so ([StreamsBody.Empty]).
 * - Downloads (where this device has them, [downloads]): every playable row offers a Download action ([download]),
 *   which queues the stream's download and says so. In [downloadMode] (Detail's Download button, an episode's
 *   "Download episode", a download without a stream) the list is for downloading: only playable streams are listed and
 *   [pick] queues the download and closes the list.
 * - Player apps ([playerApps], where this device has any): every row that plays offers "Play with…" Tortuga or one of
 *   them for this play ([playWith]); not in [downloadMode].
 * - The row over the list ([StreamsUiState.addons]): Refresh ([refresh]), then All and a chip per addon ([selectAddon]).
 *   All is selected when the list opens and shows every group; the choice isn't saved. An addon's chip shows its groups
 *   alone (their rows, notes, and a failed one's error and Retry), and the filters' line and a pick's alternatives
 *   count only those. Chips follow the groups' order, one per addon (the streams a title carries and its addon's own
 *   answer are one chip). An addon gets its chip once it answered with something to show (streams, also ones the
 *   filters hide, or a note of the ones this app can't play) or failed, so its Retry stays reachable, and keeps it while
 *   it is asked again (Refresh, Retry); an empty answer gets none, nor does an addon still to answer the first time.
 *   The chips and All show only while two or more addons have one; Refresh is always there. The selected addon stays
 *   selected while the others answer and through a refresh, as long as it has its chip; once it hasn't, All is.
 *   The list's spinner ([StreamsBody.Loading]) is still the whole list's.
 *
 * Call everything on the main thread; [release] ends it.
 */
class StreamsStore internal constructor(
    private val meta: MetaItem,
    private val videoId: String,
    private val profileId: String,
    private val aggregator: AddonAggregator,
    private val installedAddons: suspend () -> List<Descriptor>,
    private val streamHistory: StreamHistoryRepository,
    private val settings: SettingsRepository,
    private val policy: StreamListPolicy,
    private val host: StreamsHost,
    parentScope: CoroutineScope,
    private val notice: String?,
    private val downloads: DownloadManager? = null,
    private val downloadMode: Boolean = false,
    /** This device's player apps as they're found; null where the platform has none. */
    private val playerApps: Flow<List<PlayerApp>>? = null,
    private val fromStart: Boolean = false,
    /**
     * The profile's hand picks: a pick that plays is recorded with the playable rows shown around it, and while the
     * profile's suggestions are on they suggest a stream ([StreamPicks.scorer], learnt from the picks and the profile's
     * languages and sort).
     */
    private val picks: StreamPicks? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var addonStates: List<AddonLoadable<PreparedAnswer>> = emptyList()
    private var addonsKnown = false
    private var addonsUnreadable = false
    private var lastPlayed: StreamChoice? = null

    /** The profile's filters; null until read, and the answers wait for them. */
    private var filter: StreamFilter? = null
    private var filterPart: List<Any?>? = null
    private var sort: StreamSort = StreamSort.ADDON_ORDER
    private var showFiltered = false

    /** Every listed row's stream, by row id. */
    private var choices: Map<String, StreamChoice> = emptyMap()

    /** Every listed row of an answered addon, with what its text says, by row id. */
    private var entries: Map<String, ParsedEntry> = emptyMap()
    private var playableOrder: List<String> = emptyList()
    private val parsedGroups = mutableMapOf<Int, ParsedGroup>()
    private var downloadMessage: String? = null

    /** What the profile's picks taught; null until learnt, while they suggest nothing and while suggestions are off. */
    private var scorer: StreamScorer? = null
    private var languages = LanguagePrefs(emptyList(), emptyList())

    /** A pick in [downloadMode] is being queued: another pick is ignored meanwhile. */
    private var queueing = false

    /** "Play with…" over this device's player apps, built when they change. */
    private var playWith: PlayWithMenu? = null

    /** The addon whose groups alone the list shows ([selectAddon]), by transport URL; null for All. Not saved. */
    private var selectedAddon: String? = null

    /** The addons with a chip as last settled, by transport URL: an addon asked again keeps its chip meanwhile. */
    private val listedAddons = mutableSetOf<String>()

    /** Each addon's chip id ([StreamsAddon.id]) by transport URL, the same while the list is open. */
    private val addonIds = mutableMapOf<String, String>()

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<StreamsUiState> = mutableState.asStateFlow()

    /** The list's asking: [load]'s, and [reload]'s for the addons that failed; the next [load] cancels all of it. */
    private var loadJob: Job? = null

    /** The answers [reload] asked for again, by request, kept over [load]'s as they arrive; the next [load] drops them. */
    private val retried = mutableMapOf<ResourceRequest, Loadable<PreparedAnswer>>()

    init {
        scope.launch {
            settings.observe(profileId)
                .distinctUntilChanged { old, new -> old.filterPart() == new.filterPart() && old.streamSort == new.streamSort }
                .collect {
                    val part = it.filterPart()
                    if (part != filterPart) {
                        filter = StreamFilter.of(it)
                        filterPart = part
                    }
                    // A build that shows no cache state lists by the other keys (auto-play goes by them all).
                    sort = StreamSort(it.streamSort.filter(::offered))
                    publish()
                }
        }
        if (picks != null && !downloadMode) {
            scope.launch {
                settings.observe(profileId)
                    .map { Triple(it.streamSuggestions, LanguagePrefs.of(it.audioLanguages, it.subtitleLanguages), it.streamSort) }
                    .distinctUntilChanged()
                    .collect { (on, languages, sortKeys) ->
                        this@StreamsStore.languages = languages
                        scorer = if (on) picks.scorer(languages, sortKeys) else null
                        publish()
                    }
            }
        }
        playerApps?.let { found ->
            scope.launch {
                found.collect {
                    playWith = playWithMenu(it)
                    publish()
                }
            }
        }
        load()
    }

    /** Acts on the row [id]: plays it, or opens it outside the app; in [downloadMode], queues its download. */
    fun pick(id: String) {
        val choice = choices[id] ?: return
        if (downloadMode) {
            if (choice.playable && !queueing) queue(choice, closeAfter = true)
            return
        }
        when (val playability = choice.stream.playability) {
            is StreamPlayability.Playable -> host.play(playRequest(id, choice))
            is StreamPlayability.YouTube -> host.openUrl(playability.watchUrl)
            is StreamPlayability.External -> playability.deepLink?.let(host::openDeepLink) ?: host.openUrl(playability.url)
            is StreamPlayability.Unsupported -> {}
        }
    }

    /**
     * Plays the row [id] once in the player [playerId] ([StreamsUiState.playWith]: Tortuga or a player app), whatever
     * Settings → Player says. Only a row that plays; nothing in [downloadMode].
     */
    fun playWith(id: String, playerId: String) {
        val choice = choices[id] ?: return
        if (downloadMode || choice.stream.playability !is StreamPlayability.Playable) return
        host.playWith(playRequest(id, choice), playerId)
    }

    /**
     * The row [id]'s [choice] as played, with the other playable streams as alternatives (the ones after it first),
     * its pick recorded ([picks]).
     */
    private fun playRequest(id: String, choice: StreamChoice): PlayRequest {
        val index = playableOrder.indexOf(id)
        val others = (playableOrder.drop(index + 1) + playableOrder.take(index)).mapNotNull(choices::get)
        val shown = playableOrder.mapNotNull(entries::get)
        val pickId = picks?.takeIf { index >= 0 && shown.size == playableOrder.size }?.listPick(
            meta,
            videoId,
            shown.map { PickedStream.of(it.badges, StreamFilter.sizeOf(it.stream, it.badges)) },
            chosen = index,
        )
        return PlayRequest(meta, videoId, choice, others, startPositionSeconds = if (fromStart) 0.0 else null, pickId = pickId)
    }

    /** Queues the download of the row [id] ([StreamRow.downloadActionText]); [StreamsUiState.downloadMessage] says how it went. */
    fun download(id: String) {
        val choice = choices[id]?.takeIf { it.playable } ?: return
        queue(choice, closeAfter = false)
    }

    /** The download message was shown. */
    fun dismissDownloadMessage() {
        downloadMessage = null
        publish()
    }

    /** Shows the streams the filters hide in this list ([StreamsUiState.filter]), or hides them again. */
    fun toggleFiltered() {
        showFiltered = !showFiltered
        publish()
    }

    /**
     * Picks the sort menu's option [id] ([StreamsUiState.sort]): Addon order clears the sort; a key not in it goes
     * after the ones in it (largest size first and smallest size first replace each other), one in it comes out.
     * Stored as the profile's sort, synced, which every stream list and auto-play go by.
     */
    fun toggleSort(id: String) {
        val key = StreamSortKey.fromKey(id)
        scope.launch {
            settings.update(profileId, ProfileSetting.StreamSortKeys) { keys ->
                when {
                    key == null -> emptyList()
                    key in keys -> keys - key
                    else -> keys.filterNot { it in SIZE_KEYS && key in SIZE_KEYS } + key
                }
            }
        }
    }

    /**
     * Retry: asks the addons whose answer failed again (an answer still fresh in the response cache comes from there),
     * each group loading until its addon answers, the other addons' answers staying as they are; and reads the last
     * played stream again.
     */
    fun reload() {
        val job = loadJob ?: return
        readLastPlayed(job)
        val failed = addonStates.filter { it.failed }
        if (failed.isEmpty()) return
        failed.forEach { retried[it.request] = Loadable.Loading }
        addonStates = addonStates.map { it.retriedOrSelf() }
        publish()
        for (failure in failed) {
            scope.launch(job) {
                aggregator.streams(listOf(failure.addon), meta.type, videoId, prepare = ::prepare).collect { states ->
                    val answer = states.firstOrNull { it.request == failure.request } ?: return@collect
                    retried[failure.request] = answer.state
                    addonStates = addonStates.map { it.retriedOrSelf() }
                    publish()
                }
            }
        }
    }

    /**
     * Shows the groups of the addon whose chip is [id] ([StreamsAddon.id]) alone, or with null every group (All). An id
     * no chip has now does nothing.
     */
    fun selectAddon(id: String?) {
        selectedAddon = if (id == null) {
            null
        } else {
            if (mutableState.value.addons.none { it.id == id }) return
            addonIds.entries.first { it.value == id }.key
        }
        publish()
    }

    /**
     * The row's Refresh: asks every addon again past the response cache, each group loading until its addon answers
     * (the chips and the selection stay meanwhile), and reads the last played stream again.
     */
    fun refresh() = load(fresh = true)

    fun release() {
        scope.cancel()
    }

    /** Queues [choice]'s download for the profile; then closes the list ([closeAfter]) or says it did. */
    private fun queue(choice: StreamChoice, closeAfter: Boolean) {
        val downloads = downloads ?: return
        queueing = true
        scope.launch {
            val queued = try {
                downloads.enqueue(profileId, meta, videoId, choice)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "queueing a download failed" }
                false
            } finally {
                queueing = false
            }
            if (queued && closeAfter) return@launch host.closeStreams()
            downloadMessage = if (queued) T.streams.download.queued else T.streams.download.failed
            publish()
        }
    }

    /** Asks every addon; [fresh]: past the response cache. */
    private fun load(fresh: Boolean = false) {
        loadJob?.cancel()
        retried.clear()
        val job = SupervisorJob(scope.coroutineContext[Job])
        loadJob = job
        readLastPlayed(job)
        scope.launch(job) {
            addonsUnreadable = false
            val addons = readingAddons(TAG) { installedAddons() } ?: run {
                addonsUnreadable = true
                publish()
                return@launch
            }
            aggregator.streams(addons, meta, videoId, fresh, ::prepare).collect { states ->
                addonStates = states.map { it.retriedOrSelf() }
                addonsKnown = true
                publish()
            }
        }
    }

    /** Reads the last stream played, a hint on top of the list: a read that fails leaves it out. */
    private fun readLastPlayed(job: Job) {
        scope.launch(job) {
            lastPlayed = try {
                streamHistory.lastStream(profileId, meta.id, videoId)?.let { StreamChoice.fromHistory(it, addonName = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "reading the last stream played failed (${e::class.simpleName})" }
                null
            }
            publish()
        }
    }

    /** This addon's answer as [reload] asked for it again, if it did. */
    private fun AddonLoadable<PreparedAnswer>.retriedOrSelf(): AddonLoadable<PreparedAnswer> =
        retried[request]?.let { copy(state = it) } ?: this

    /** The addon failed: its group shows the error and Retry (an empty answer is no failure). */
    private val AddonLoadable<*>.failed: Boolean
        get() = (state as? Loadable.Error)?.error.let { it != null && it != AddonError.EmptyContent }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): StreamsUiState {
        val filter = filter
        val rows = mutableMapOf<String, StreamChoice>()
        val rowEntries = mutableMapOf<String, ParsedEntry>()
        val built = addonStates.mapIndexed { groupIndex, addonState ->
            val addon = addonState.addon
            val id = groupId(groupIndex)
            val name = addon.manifest.name
            when (val loadable = addonState.state) {
                Loadable.Loading -> BuiltGroup(addon, loadingGroup(id, name))
                is Loadable.Error ->
                    if (loadable.error == AddonError.EmptyContent) {
                        BuiltGroup(addon, StreamGroup(id, name, GroupStatus.Ready, streams = emptyList(), unplayableNote = null, filteredNote = null))
                    } else {
                        val failed = StreamGroup(id, name, GroupStatus.Failed(loadable.error.message), streams = emptyList(), unplayableNote = null, filteredNote = null)
                        BuiltGroup(addon, failed, listable = true)
                    }
                is Loadable.Ready -> if (filter == null) {
                    BuiltGroup(addon, loadingGroup(id, name))
                } else {
                    // An addon's answer is parsed once per filter: later updates are other addons settling.
                    val parsed = parsedGroups[groupIndex]?.takeIf { it.source === loadable && it.lastPlayed === lastPlayed && it.filter === filter }
                        ?: parse(groupIndex, addonState.addon, loadable, filter).also { parsedGroups[groupIndex] = it }
                    rows.putAll(parsed.choices)
                    parsed.entries.associateByTo(rowEntries) { it.row.id }
                    BuiltGroup(
                        addon,
                        if (showFiltered) parsed.unfiltered else parsed.filtered,
                        hidden = parsed.hidden,
                        playable = if (showFiltered) parsed.playable else parsed.playableShown,
                        listable = parsed.unfiltered.streams.isNotEmpty() || parsed.unfiltered.unplayableNote != null,
                        parsed = parsed,
                    )
                }
            }
        }
        val chips = addonChips(built)
        val shown = selectedAddon?.let { url -> built.filter { it.addon.transportUrl == url } } ?: built
        val sorted = sortedGroups(shown)
        // A group that answered with nothing to show leaves the list, rather than its heading standing alone.
        val listed = (sorted?.let { listOf(it.group) + it.rest.map(BuiltGroup::group) } ?: shown.map { it.group }).filter {
            it.status != GroupStatus.Ready || it.streams.isNotEmpty() || it.unplayableNote != null || it.filteredNote != null
        }
        val hidden = shown.sumOf { it.hidden }
        // One group's count of the streams the filters hide is the filter bar's: only several groups' counts say more. A
        // group whose every stream the filters hide keeps it: it is all the group shows.
        val groups = if (listed.count { it.filteredNote != null } == 1) {
            listed.map { if (it.filteredNote == null || it.streams.isEmpty()) it else it.copy(filteredNote = null) }
        } else {
            listed
        }
        choices = rows
        entries = rowEntries
        playableOrder = sorted?.playable ?: shown.flatMap { it.playable }
        val loading = !addonsUnreadable && (!addonsKnown || built.any { it.group.status == GroupStatus.Loading })
        val marked = if (loading) groups else suggested(groups)
        val emptyText = when {
            addonsUnreadable -> T.addons.unreadable
            // A group's note of the streams this app can't play is something to show: the list stays.
            !loading && hidden == 0 && groups.all { it.streams.isEmpty() && it.unplayableNote == null } -> T.streams.empty
            else -> null
        }
        return StreamsUiState(
            title = meta.name,
            subtitle = meta.videos.firstOrNull { it.id == videoId }?.episodeLine(),
            body = when {
                groups.isEmpty() && loading -> StreamsBody.Loading
                // A failed addon offers its Retry in the list.
                emptyText != null && groups.none { it.status is GroupStatus.Failed } -> StreamsBody.Empty(emptyText)
                else -> StreamsBody.Groups(marked)
            },
            notice = notice ?: if (downloadMode) T.streams.download.notice else null,
            filter = if (hidden == 0) {
                null
            } else if (showFiltered) {
                StreamFilterBar(T.streams.filters.showing(hidden), T.streams.filters.hide, showing = true)
            } else {
                StreamFilterBar(T.streams.filters.hidden(hidden), T.streams.filters.show, showing = false)
            },
            downloadMode = downloadMode,
            downloadMessage = downloadMessage,
            playWith = if (downloadMode) null else playWith,
            addons = chips,
            sort = sortMenu(),
        )
    }

    /**
     * [groups] with the stream [scorer] suggests marked ([StreamRow.suggestion]): of the playable rows the filters let
     * through and this device shows as they are made to be shown ([StreamScorer.showable]), in the list's order
     * ([StreamScorer.best] lets it count while the picks are few), where the sort put it; as they are without a scorer,
     * or when nothing sets one row apart.
     */
    private fun suggested(groups: List<StreamGroup>): List<StreamGroup> {
        val scorer = scorer ?: return groups
        val context = picks?.context() ?: return groups
        val candidates = playableOrder.mapNotNull(entries::get).filter { it.passes }
            .map { it to PickedStream.of(it.badges, StreamFilter.sizeOf(it.stream, it.badges)) }
            .filter { (_, stream) -> StreamScorer.showable(stream, context) }
        if (candidates.size < 2) return groups
        val rows = candidates.map { it.first }
        val streams = candidates.map { it.second }
        val runtime = DetailText.runtimeMinutes(meta.preview.runtime)?.let { it * 60L }
        val best = scorer.best(streams.map { StreamScorer.features(it, context, runtime, languages) }) ?: return groups
        val reason = StreamSuggestionText.reason(best, streams[best.index], languages, policy.cacheBadge) ?: return groups
        val id = rows[best.index].row.id
        val mark = StreamSuggestion(T.streams.suggested.label, reason)
        return groups.map { group ->
            if (group.streams.none { it.id == id }) group else group.copy(streams = group.streams.map { if (it.id == id) it.copy(suggestion = mark) else it })
        }
    }

    /**
     * With a sort, the rows of [shown]'s answered groups as one group in its order ([StreamGroup.sorted]: the filters'
     * and the unplayable streams' counts its own), with its playable rows' ids, and the groups still answering or failed
     * to follow it; null in the addons' order, or while none has answered.
     */
    private fun sortedGroups(shown: List<BuiltGroup>): SortedGroups? {
        if (sort.addonOrder) return null
        val answered = shown.mapNotNull { it.parsed }
        if (answered.isEmpty()) return null
        val entries = sort.sorted(answered.flatMap { group -> group.entries.filter { showFiltered || it.passes } }, { it.stream }, { it.badges })
        val hidden = answered.sumOf { it.hidden }
        val unplayable = answered.sumOf { it.unplayable }
        val group = StreamGroup(
            id = SORTED_GROUP,
            addonName = sortLabel(),
            status = GroupStatus.Ready,
            streams = entries.map { it.row },
            unplayableNote = if (policy.unplayableNote && unplayable > 0) T.streams.unplayable(unplayable) else null,
            filteredNote = if (!showFiltered && hidden > 0) T.streams.filters.hidden(hidden) else null,
            sorted = true,
        )
        return SortedGroups(group, entries.filterNot { it.row.external }.map { it.row.id }, shown.filter { it.parsed == null })
    }

    /** The sort menu over the list: Addon order, then each key this build offers ([offered]). */
    private fun sortMenu(): StreamSortMenu {
        val keys = sort.keys
        val offered = StreamSortKey.entries.filter(::offered)
        return StreamSortMenu(
            title = T.streams.sort.title,
            label = sortLabel(),
            sorted = keys.isNotEmpty(),
            options = listOf(SettingChoiceOption(ADDON_ORDER, T.streams.sort.addonOrder, selected = keys.isEmpty())) +
                offered.map { SettingChoiceOption(it.key, it.label(), selected = it in keys) },
        )
    }

    /** What the list goes by: "Addon order", or the keys in order ("Resolution, Largest size first"). */
    private fun sortLabel(): String =
        if (sort.addonOrder) T.streams.sort.addonOrder else sort.keys.joinToString(T.settings.listSeparator) { it.label() }

    /** A sort key this build's lists go by: all but the cache state on builds that show none ([StreamListPolicy.cacheBadge]). */
    private fun offered(key: StreamSortKey): Boolean = key != StreamSortKey.CACHED || policy.cacheBadge

    private fun StreamSortKey.label(): String = when (this) {
        StreamSortKey.RESOLUTION -> T.streams.sort.resolution
        StreamSortKey.SOURCE -> T.streams.sort.source
        StreamSortKey.SIZE_LARGEST -> T.streams.sort.sizeLargest
        StreamSortKey.SIZE_SMALLEST -> T.streams.sort.sizeSmallest
        StreamSortKey.CACHED -> T.streams.sort.cached
    }

    /**
     * The row's addon chips in the groups' order, one per addon, with [selectedAddon] kept to them: an addon has one
     * once a group of it is [BuiltGroup.listable], and keeps it while it is asked again ([listedAddons]). Under two such
     * addons there are none, and the list shows every group.
     */
    private fun addonChips(built: List<BuiltGroup>): List<StreamsAddon> {
        val listed = built.groupBy { it.addon.transportUrl }.filter { (url, groups) ->
            val listable = groups.any { it.listable }
            if (groups.none { it.group.status == GroupStatus.Loading }) {
                if (listable) listedAddons += url else listedAddons -= url
            }
            listable || url in listedAddons
        }
        selectedAddon = selectedAddon?.takeIf { listed.size >= 2 && it in listed }
        if (listed.size < 2) return emptyList()
        return listed.map { (url, groups) -> StreamsAddon(idOf(url), groups.first().addon.manifest.name, selected = url == selectedAddon) }
    }

    private fun idOf(transportUrl: String): String = addonIds.getOrPut(transportUrl) { "addon-${addonIds.size + 1}" }

    /** [StreamGroup.id] of the group of the [index]th addon state: the list asks the same addons in the same order. */
    private fun groupId(index: Int) = "group-$index"

    private fun loadingGroup(id: String, name: String) =
        StreamGroup(id, name, GroupStatus.Loading, streams = emptyList(), unplayableNote = null, filteredNote = null)

    /**
     * One addon's answer read for its rows, on the aggregator's parse dispatcher: each listed stream's tidied text and
     * badges, less the ones the text already shows; unplayable sources are counted, not listed. In [downloadMode]
     * streams that open outside the app are left out: they can't be downloaded.
     */
    private fun prepare(addon: Descriptor, streams: List<Stream>): PreparedAnswer {
        val listed = mutableListOf<PreparedStream>()
        var unplayable = 0
        streams.forEachIndexed { index, stream ->
            val playability = stream.playability
            if (playability is StreamPlayability.Unsupported) {
                unplayable++
                return@forEachIndexed
            }
            if (downloadMode && playability !is StreamPlayability.Playable) return@forEachIndexed
            val badges = StreamBadgeParser.parse(stream)
            val name = StreamText.name(stream.name) ?: addon.manifest.name
            val description = StreamText.description(stream.description)
            val shown = badges.let { if (policy.cacheBadge) it else it.copy(cached = null) }.display()
                .notShownIn(if (description == null) name else "$name\n$description")
            listed += PreparedStream(index, stream, external = playability !is StreamPlayability.Playable, badges, name, description, shown)
        }
        return PreparedAnswer(streams, listed, unplayable)
    }

    /** One addon's prepared answer as rows under [filter]; filtered ones are set apart. */
    private fun parse(groupIndex: Int, addon: Descriptor, answer: Loadable.Ready<PreparedAnswer>, filter: StreamFilter): ParsedGroup {
        val name = addon.manifest.name
        val choices = mutableMapOf<String, StreamChoice>()
        val playable = mutableListOf<String>()
        val playableShown = mutableListOf<String>()
        val all = mutableListOf<StreamRow>()
        val shown = mutableListOf<StreamRow>()
        val entries = mutableListOf<ParsedEntry>()
        val remembered = lastPlayed?.let { StreamChoice.indexOfRemembered(answer.content.streams, addon.transportUrl, it) } ?: -1
        for (stream in answer.content.listed) {
            val id = "$groupIndex:${stream.index}"
            val passes = filter.accepts(stream.stream, stream.parsed)
            choices[id] = StreamChoice(stream.stream, addon.transportUrl, name)
            val row = row(id, stream.name, stream.description, stream.external, stream.badges, passes, lastUsed = stream.index == remembered)
            all += row
            entries += ParsedEntry(row, stream.stream, stream.parsed, passes)
            if (passes) shown += row
            if (!stream.external) {
                playable += id
                if (passes) playableShown += id
            }
        }
        val hidden = all.size - shown.size
        val unplayable = answer.content.unplayable
        val unplayableNote = if (policy.unplayableNote && unplayable > 0) T.streams.unplayable(unplayable) else null
        return ParsedGroup(
            source = answer,
            lastPlayed = lastPlayed,
            filter = filter,
            filtered = StreamGroup(groupId(groupIndex), name, GroupStatus.Ready, shown, unplayableNote, if (hidden > 0) T.streams.filters.hidden(hidden) else null),
            unfiltered = StreamGroup(groupId(groupIndex), name, GroupStatus.Ready, all, unplayableNote, filteredNote = null),
            hidden = hidden,
            unplayable = unplayable,
            entries = entries,
            choices = choices,
            playable = playable,
            playableShown = playableShown,
        )
    }

    private fun row(id: String, name: String, description: String?, external: Boolean, badges: List<StreamBadge>, passes: Boolean, lastUsed: Boolean) =
        StreamRow(
            id = id,
            name = name,
            description = description,
            badges = badges,
            external = external,
            lastUsed = lastUsed,
            filtered = !passes,
            noteKind = when {
                lastUsed -> StreamNoteKind.LAST_USED
                !passes -> StreamNoteKind.FILTERED
                external -> StreamNoteKind.EXTERNAL
                else -> null
            },
            downloadActionText = if (downloads != null && !downloadMode && !external) T.streams.download.action else null,
        )

    /** The filters' part of the settings: a change elsewhere in them doesn't parse the answers again. */
    private fun ProfileSettings.filterPart(): List<Any?> = listOf(
        hiddenStreamQualities, hiddenStreamRanges, hiddenStreamCodecs, streamMinSizeMb, streamMaxSizeMb,
        streamIncludePattern, streamExcludePattern,
    )

    /**
     * A group as the list builds it, with its [addon]: how many of its streams the filters hide, its listed playable
     * rows' ids, whether it gives its addon a chip ([listable]: streams, also ones the filters hide, a note of the
     * ones this app can't play, or a failure), and its answer as rows ([parsed]) once there is one.
     */
    private class BuiltGroup(
        val addon: Descriptor,
        val group: StreamGroup,
        val hidden: Int = 0,
        val playable: List<String> = emptyList(),
        val listable: Boolean = false,
        val parsed: ParsedGroup? = null,
    )

    /** The sorted list ([sortedGroups]): its one [group] of rows, their [playable] ids, and the groups after it. */
    private class SortedGroups(val group: StreamGroup, val playable: List<String>, val rest: List<BuiltGroup>)

    /** An addon's answer as [prepare] read it: its [streams], those listed in order, and how many it can't play. */
    private class PreparedAnswer(val streams: List<Stream>, val listed: List<PreparedStream>, val unplayable: Int)

    /** A listed stream, [index] in its answer: its row's text, what its text says ([parsed]) and the badges the text doesn't show. */
    private class PreparedStream(
        val index: Int,
        val stream: Stream,
        val external: Boolean,
        val parsed: StreamBadges,
        val name: String,
        val description: String?,
        val badges: List<StreamBadge>,
    )

    /** A row of a parsed answer, with what the sort reads of it and whether it passes the filters. */
    private class ParsedEntry(val row: StreamRow, val stream: Stream, val badges: StreamBadges, val passes: Boolean)

    /**
     * One addon's answer parsed under one filter: the group as it shows with the filtered streams hidden ([filtered])
     * and shown ([unfiltered]), and the row ids of its playable streams, all of them and those passing the filter.
     */
    private class ParsedGroup(
        val source: Loadable.Ready<PreparedAnswer>,
        val lastPlayed: StreamChoice?,
        val filter: StreamFilter,
        val filtered: StreamGroup,
        val unfiltered: StreamGroup,
        val hidden: Int,
        val unplayable: Int,
        val entries: List<ParsedEntry>,
        val choices: Map<String, StreamChoice>,
        val playable: List<String>,
        val playableShown: List<String>,
    )

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "streams"

        private const val TAG = "streams"

        /** The sorted list's one group ([StreamGroup.id]). */
        private const val SORTED_GROUP = "sorted"

        /** The sort menu's Addon order ([toggleSort]): no key has this id. */
        private const val ADDON_ORDER = "addon"

        private val SIZE_KEYS = setOf(StreamSortKey.SIZE_LARGEST, StreamSortKey.SIZE_SMALLEST)
    }
}
