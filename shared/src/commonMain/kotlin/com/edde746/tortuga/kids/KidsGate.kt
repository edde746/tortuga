package com.edde746.tortuga.kids

import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.MetaItemPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Whether the profile [profile] runs as a kids profile: it is one, or it isn't read yet (null), which counts as one
 * until it is, so nothing a kids profile may not see or open shows meanwhile. Every kids rule asks this.
 */
internal fun isKidsProfile(profile: Profile?): Boolean = profile?.kids != false

/**
 * A profile's kids rules where titles are listed, opened and played. While the profile isn't a kids profile ([active]
 * false) every title passes and no suggested age is looked up; on a kids profile a title passes only once
 * [KidsPolicy] allows it with its rating ([ratings]: none while the trackers aren't set up, so every lookup fails and
 * only a parent's approvals pass).
 *
 * Ratings are looked up as titles are asked about, a row of cards asking only about those it is about to show
 * ([walk]), and kept for the gate's life; a lookup that failed is asked again once [RETRY_FAILED] has passed, when
 * [changes] moves for it. [changes] moves whenever a verdict may have: the profile, its kids settings, a rating
 * arriving, a failed lookup due again. Main thread only.
 */
internal class KidsGate(
    private val profileId: String,
    profile: Flow<Profile?>,
    settings: Flow<ProfileSettings>,
    private val settingsRepository: SettingsRepository,
    private val ratings: () -> AgeRatings?,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    /** The profile's rules once read: [kids] null for a profile that isn't a kids profile ([isKidsProfile]). */
    private data class Rules(val kids: KidsPolicy?, val homeCatalogs: List<HomeCatalog>)

    private val mutableRules = MutableStateFlow<Rules?>(null)
    private val mutableActive = MutableStateFlow(isKidsProfile(profile = null))
    private val mutableChanges = MutableStateFlow(0)

    /** The profile runs as a kids profile ([isKidsProfile]: also until it is read). */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    /** Moves whenever a verdict may have changed: stores that list titles through [shows] publish again on it. */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    /** The Home rows a kids profile starts with ([Profile.homeCatalogs]), once the profile is read; empty for any other. */
    val homeCatalogs: Flow<List<HomeCatalog>> = mutableRules.filterNotNull().map { it.homeCatalogs }.distinctUntilChanged()

    private class Known(val rating: TitleRating, val at: ComparableTimeMark)

    private val known = HashMap<String, Known>()
    private val asking = HashMap<String, Deferred<TitleRating>>()

    /** The last lookup failure, and the timer moving [changes] once [RETRY_FAILED] has passed since ([retryFailed]). */
    private lateinit var lastFailure: ComparableTimeMark
    private var retry: Job? = null

    init {
        scope.launch {
            combine(profile, settings) { profile, settings ->
                if (isKidsProfile(profile)) {
                    Rules(KidsPolicy.of(settings), profile?.homeCatalogs.orEmpty())
                } else {
                    Rules(null, emptyList())
                }
            }.collect { rules ->
                if (rules == mutableRules.value) return@collect
                mutableRules.value = rules
                mutableActive.value = rules.kids != null
                mutableChanges.value++
            }
        }
    }

    /**
     * Whether the title [id] of [type], with the catalog item [preview] when there is one, may be listed: false while
     * its verdict isn't known (the profile not read yet, its rating on its way, which this starts); true for every
     * title on a profile that isn't a kids profile.
     */
    fun shows(type: String, id: String, preview: MetaItemPreview?): Boolean = verdictNow(type, id, preview) == KidsVerdict.Allowed

    /** The verdict on the title [id] of [type] as known now; null while it isn't (see [shows]). */
    fun verdictNow(type: String, id: String, preview: MetaItemPreview?): KidsVerdict? {
        val policy = (mutableRules.value ?: return null).kids ?: return KidsVerdict.Allowed
        return judge(policy, type, id, preview)
    }

    /**
     * A card of a list the gate filters, as much of it as the gate reads: its title's [type] and [id], and the catalog
     * item [preview] when the list has it.
     */
    interface Card {
        val type: String
        val id: String
        val preview: MetaItemPreview?
    }

    /**
     * How far a list of cards goes on a kids profile ([walk]): it wants [ROW_CARDS] cards, and more each time the user
     * reaches its end while it shows as many as it wants ([extend]).
     */
    private class Reach {
        var want = ROW_CARDS
            private set

        /**
         * The user reached the end of the list [walk] went over: when it shows as many cards as it wants
         * ([Walk.full]), it wants [ROW_CARDS] more. A list showing fewer goes further only by filling ([Walk.fills]).
         * True when it wants more.
         */
        fun extend(walk: Walk<*>?): Boolean {
            if (walk?.full != true) return false
            want += ROW_CARDS
            return true
        }
    }

    /**
     * A list's cards as [walk] went over them.
     *
     * @param shown the cards the list shows, in order.
     * @param judging a card the walk reached waits for its verdict (or the profile isn't read yet).
     * @param stopped the walk ended before the last card: the list shows, or waits on, as many as it wants.
     * @param full the list shows as many cards as it wants on a kids profile: reaching its end makes it want more
     *   ([Reach.extend]).
     * @param fills the list's next page should load: the walk reached the last card, knows every verdict, shows fewer
     *   cards than the list wants, and none of them is unchecked (a failed lookup stops the filling until
     *   [RETRY_FAILED] has passed and the title is asked again).
     */
    class Walk<T : Card>(val shown: List<T>, val judging: Boolean, val stopped: Boolean, val full: Boolean, val fills: Boolean)

    /**
     * Walks a list's [cards] in order. On a kids profile the list shows the cards allowed, and the walk looks ratings
     * up only while the list shows and waits on fewer cards than [reach] wants: the cards after it stops stay hidden
     * and unasked. On any other profile every card shows; none while the profile isn't read.
     */
    private fun <T : Card> walk(cards: List<T>, reach: Reach): Walk<T> {
        val rules = mutableRules.value ?: return Walk(emptyList(), judging = cards.isNotEmpty(), stopped = false, full = false, fills = false)
        val policy = rules.kids ?: return Walk(cards, judging = false, stopped = false, full = false, fills = false)
        val shown = ArrayList<T>()
        var pending = 0
        var walked = 0
        var unchecked = false
        for (card in cards) {
            if (shown.size + pending >= reach.want) break
            when (val verdict = judge(policy, card.type, card.id, card.preview)) {
                null -> pending++
                KidsVerdict.Allowed -> shown += card
                is KidsVerdict.Blocked -> if (verdict.reason == KidsBlockReason.Unchecked) unchecked = true
            }
            walked++
        }
        val end = walked == cards.size
        val full = shown.size >= reach.want
        return Walk(shown, judging = pending > 0, stopped = !end, full = full, fills = end && pending == 0 && !full && !unchecked)
    }

    /**
     * How a row or grid of cards pages through [gate], the one rule of every list a kids profile filters: the list
     * shows what its walk over its cards allows ([walk]); reaching its end while it shows as many cards as it wants
     * makes it want more, and asks for its next page only once the walk went through every card loaded ([end]); one
     * that went through its cards showing too few loads its next pages by itself ([fill]). Without a gate, and on a
     * profile that isn't a kids profile, it pages as any list does. One per list, kept while its catalog stays.
     */
    class Paging<T : Card>(private val gate: KidsGate?) {
        private val reach = Reach()

        /** What the last [walk] found; null without a gate, and before the first. */
        var walk: Walk<T>? = null
            private set

        /** Walks the list's [cards], in order ([Walk.shown]: the cards it shows); null without a gate. */
        fun walk(cards: List<T>): Walk<T>? = gate?.walk(cards, reach).also { walk = it }

        /**
         * Loads the list's next page ([loadNext]) when the last walk asks to fill ([Walk.fills]), nothing holds the
         * list up ([busy]: a page on its way, or the list not ready), and fewer than [FILL_PAGES] pages were [asked]
         * after the first. True when it asked.
         */
        fun fill(asked: Int, busy: Boolean, loadNext: () -> Boolean): Boolean =
            walk?.fills == true && !busy && asked < FILL_PAGES && loadNext()

        /**
         * The user reached the list's end: on a kids profile, a list showing as many cards as it wants wants more
         * ([Reach.extend]) and loads its next page ([loadNext]) only once the walk went through every card loaded; a
         * list showing fewer changes nothing (it goes further only by filling). Elsewhere it loads its next page. True
         * when the list changed.
         */
        fun end(loadNext: () -> Boolean): Boolean {
            if (gate?.active?.value != true) return loadNext()
            if (!reach.extend(walk)) return false
            if (walk?.stopped != true) loadNext()
            return true
        }

        /**
         * Reaching the list's end goes further ([end]), [hasNext] saying whether its catalog has a page after those
         * loaded: on a kids profile only while the list shows as many cards as it wants.
         */
        fun goesFurther(hasNext: Boolean): Boolean {
            if (gate?.active?.value != true) return hasNext
            val walk = walk ?: return false
            return walk.full && (walk.stopped || hasNext)
        }
    }

    /**
     * [policy]'s verdict on the title [id] of [type] as known now; null while its rating isn't. Its rating is looked up
     * when one is due (never looked up, or its lookup failed [RETRY_FAILED] ago or more) and none is on its way.
     */
    private fun judge(policy: KidsPolicy, type: String, id: String, preview: MetaItemPreview?): KidsVerdict? {
        val key = keyOf(type, id, preview)
        val live = preview?.isLive ?: (type == LIVE_TYPE)
        policy.settled(key, live)?.let { return it }
        val cacheKey = cacheKey(type, id, preview)
        val entry = known[cacheKey]
        val due = entry == null || entry.rating == TitleRating.Failed && timeSource.markNow() - entry.at >= RETRY_FAILED
        if (due && cacheKey !in asking) ask(cacheKey, type, id, preview)
        return entry?.let { policy.decide(key, live, it.rating, preview?.certifications.orEmpty()) }
    }

    /** The verdict on the title [id] of [type] with the catalog item [preview], once the profile and its rating are known. */
    suspend fun verdict(type: String, id: String, preview: MetaItemPreview?): KidsVerdict {
        val policy = mutableRules.first { it != null }?.kids ?: return KidsVerdict.Allowed
        val key = keyOf(type, id, preview)
        val live = preview?.isLive ?: (type == LIVE_TYPE)
        policy.settled(key, live)?.let { return it }
        val cacheKey = cacheKey(type, id, preview)
        val entry = known[cacheKey]
        val rating = if (entry != null && (entry.rating != TitleRating.Failed || timeSource.markNow() - entry.at < RETRY_FAILED)) {
            entry.rating
        } else {
            ask(cacheKey, type, id, preview).await()
        }
        // The rules as they are now: a parent may have changed them while the rating was on its way.
        val now = mutableRules.value?.kids ?: return KidsVerdict.Allowed
        return now.decide(key, live, rating, preview?.certifications.orEmpty())
    }

    /** The title [id] of [type] as a parent's lists keep it ([KidsTitle.id]: its IMDb id when known). */
    fun titleOf(type: String, id: String, preview: MetaItemPreview?, name: String): KidsTitle = KidsTitle(idOf(id, preview), type, name)

    /**
     * A parent allowed [title] on this profile: it joins `kids.allowed` (and leaves `kids.blocked`). Each list is
     * changed as stored ([SettingsRepository.update]): one that can't be read isn't rewritten unless the title changes it.
     */
    suspend fun allow(title: KidsTitle) {
        settingsRepository.update(profileId, ProfileSetting.KidsBlocked) { blocked -> blocked.filterNot { it.key == title.key } }
        settingsRepository.update(profileId, ProfileSetting.KidsAllowed) { allowed -> allowed.filterNot { it.key == title.key } + title }
    }

    /** A parent blocked [title] on this profile: it joins `kids.blocked` (and leaves `kids.allowed`), as [allow] does. */
    suspend fun block(title: KidsTitle) {
        settingsRepository.update(profileId, ProfileSetting.KidsAllowed) { allowed -> allowed.filterNot { it.key == title.key } }
        settingsRepository.update(profileId, ProfileSetting.KidsBlocked) { blocked -> blocked.filterNot { it.key == title.key } + title }
    }

    /**
     * Gives [title]'s entry in `kids.allowed` its name where its key is all it has for one: a title a parent allowed
     * before anything named it (opened from a link, nothing of it loaded).
     */
    suspend fun nameAllowed(title: KidsTitle) {
        if (title.name.isBlank()) return
        settingsRepository.update(profileId, ProfileSetting.KidsAllowed) { allowed ->
            allowed.map { if (it.key == title.key && it.name == it.id) it.copy(name = title.name) else it }
        }
    }

    /**
     * Looks up the rating of the title [id] of [type] with [preview] (kept under [cacheKey]) once at a time; the answer
     * is kept and moves [changes], and a failed one moves it again once [RETRY_FAILED] has passed ([retryFailed]).
     */
    private fun ask(cacheKey: String, type: String, id: String, preview: MetaItemPreview?): Deferred<TitleRating> {
        asking[cacheKey]?.let { return it }
        val lookup = scope.async(start = CoroutineStart.LAZY) {
            val rating = try {
                ratings()?.rating(type, id, preview?.imdbId) ?: TitleRating.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "rating lookup failed (${e::class.simpleName})" }
                TitleRating.Failed
            }
            val at = timeSource.markNow()
            known[cacheKey] = Known(rating, at)
            asking.remove(cacheKey)
            mutableChanges.value++
            if (rating == TitleRating.Failed) retryFailed(at)
            rating
        }
        asking[cacheKey] = lookup
        lookup.start()
        return lookup
    }

    /**
     * A lookup failed [at] then: one timer for every failure moves [changes] once [RETRY_FAILED] has passed since the
     * first, and again until it has passed since the last, so the stores walk again and ask about those titles again.
     */
    private fun retryFailed(at: ComparableTimeMark) {
        lastFailure = at
        if (retry?.isActive == true) return
        retry = scope.launch {
            var wait = RETRY_FAILED
            while (wait > Duration.ZERO) {
                delay(wait)
                mutableChanges.value++
                wait = RETRY_FAILED - lastFailure.elapsedNow()
            }
        }
    }

    companion object {
        /** How long a failed lookup stands before the title is asked about again. */
        val RETRY_FAILED: Duration = 1.minutes

        /**
         * The cards a kids list ([walk]) wants at first, and wants more of each time the user reaches its end while it
         * shows as many ([Reach.extend]). A list that shows fewer once its walk went through every card loads its next
         * page itself ([Paging.fill]), while its catalog has one, at most [FILL_PAGES] pages after its first.
         */
        const val ROW_CARDS = 10
        const val FILL_PAGES = 5
        private const val TAG = "kids"

        /** Stremio's type of a live TV channel. */
        private const val LIVE_TYPE = "tv"

        /** The id a parent's rule keeps for the title [id] ([KidsTitle.id]): its IMDb id when it or [preview] has one. */
        private fun idOf(id: String, preview: MetaItemPreview?): String = if (ImdbId.parse(id) != null) id else preview?.imdbId ?: id

        private fun keyOf(type: String, id: String, preview: MetaItemPreview?): String = KidsTitle.keyOf(type, idOf(id, preview))

        /** A rating as it was looked up: by the title's type and id, and the IMDb id its [preview] adds ([AgeRatings.rating]). */
        private fun cacheKey(type: String, id: String, preview: MetaItemPreview?) = "$type/$id/${preview?.imdbId.orEmpty()}"
    }
}
