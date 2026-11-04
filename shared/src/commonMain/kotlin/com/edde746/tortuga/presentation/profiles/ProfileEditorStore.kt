package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.kids.KidsTitle
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.board.catalog
import com.edde746.tortuga.presentation.board.catalogRowTitle
import com.edde746.tortuga.presentation.board.rowTitle
import com.edde746.tortuga.presentation.board.typeLabel
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One avatar in the editor's picker. */
data class AvatarChoice(val avatar: ProfileAvatar, val selected: Boolean)

enum class PinSetupStep {
    /** The new PIN. */
    ENTER,

    /** The same PIN again. */
    CONFIRM,
}

/**
 * Setting a PIN: four digits, then the same four again.
 *
 * @param title "Enter a 4-digit PIN" or "Enter the PIN again".
 * @param entered digits in so far (the pad shows dots).
 * @param error "The PINs didn't match. Enter a new one." after a mismatch.
 */
data class PinSetupUiState(val step: PinSetupStep, val title: String, val entered: Int, val length: Int, val error: String?) {
    /** The pad's dots, spoken ([pinEnteredText]). */
    val enteredText: String
        get() = pinEnteredText(entered, length)
}

/**
 * One catalog in the Kids profile section's Home rows list.
 *
 * @param title "Family movies", "Popular · Movies".
 * @param subtitle the addon's name, or "Its addon isn't installed on this profile."
 * @param selected the kids profile's Home starts with it as a row.
 */
data class KidsCatalogChoice(val title: String, val subtitle: String, val selected: Boolean)

/**
 * One step of the Kids profile section's highest suggested age.
 *
 * @param title "Little", "Kids" or "Teen".
 * @param ages the suggested ages it shows: "Suggested 0+", "Little, plus suggested 6+", ...
 * @param selected the profile shows titles suggested for ages up to this step.
 */
data class KidsCapChoice(val step: AgeStep, val title: String, val ages: String, val selected: Boolean)

/**
 * A title a parent allowed or blocked on the kids profile (from its page), in the section's lists.
 *
 * @param key what [ProfileEditorStore.removeAllowed] and [ProfileEditorStore.removeBlocked] take.
 * @param title the title's name.
 * @param subtitle its type: "Movie", "Series".
 * @param removeLabel the remove button's label: "Remove Frozen".
 */
data class KidsTitleRow(val key: String, val title: String, val subtitle: String, val removeLabel: String)

/**
 * The editor's Kids profile section. Static labels (Kids profile, its subtitle, Highest suggested age, Allow unrated titles
 * and its note, Home rows and its note, Allowed titles, Blocked titles, their empty lines, the lines on leaving the
 * profile, what is off and where videos come from) come from `T.profiles.editor.kids`.
 *
 * @param enabled the profile is a kids profile, or will be once saved.
 * @param canSwitch the switch takes a change: on to off always, off to on only while [blockedReason] is null (and
 *   once the other profiles are read).
 * @param blockedReason why the profile can't be a kids profile: it isn't new ("Only a new profile can be a kids
 *   profile."), or no other profile that isn't one has a PIN ("Set a PIN on an adult profile first: …").
 * @param caps while [enabled], the highest suggested age the profile shows: Little, Kids, Teen
 *   ([ProfileEditorStore.setKidsCap]).
 * @param allowUnrated titles with no suggested age show ([ProfileEditorStore.setAllowUnrated]).
 * @param homeCatalogs while [enabled], the Home rows it starts with, optional (titles in them still have to pass the
 *   suggested age): the family ones (Cinemeta's Family movies and series), the catalogs Home shows from the profile's addons
 *   (a new profile's: the default ones), then picked ones neither has; [ProfileEditorStore.setHomeCatalog] takes an
 *   index into it.
 * @param allowed while [enabled], the titles a parent allowed whatever their suggested age ([ProfileEditorStore.removeAllowed]).
 * @param blocked while [enabled], the titles a parent blocked whatever their suggested age ([ProfileEditorStore.removeBlocked]).
 */
data class KidsSectionUiState(
    val enabled: Boolean,
    val canSwitch: Boolean,
    val blockedReason: String?,
    val caps: List<KidsCapChoice>,
    val allowUnrated: Boolean,
    val homeCatalogs: List<KidsCatalogChoice>,
    val allowed: List<KidsTitleRow>,
    val blocked: List<KidsTitleRow>,
)

/**
 * The profile editor (create, or edit one profile). Static labels (Name, Avatar, Set a PIN, Remove PIN, Save, Delete
 * profile) come from `T.profiles.editor`.
 *
 * @param creating a new profile (no delete; the save button says "Create profile").
 * @param loaded the profile being edited is read (always true when creating).
 * @param nameError why the name can't be saved (too long; blank after a save attempt).
 * @param initial the name's first letter on the [avatar] tile preview.
 * @param hasPin the profile will be locked with a PIN once saved.
 * @param pinStatus "No PIN" or "Locked with a PIN", for [hasPin].
 * @param pinSetup the PIN pad while a PIN is being set.
 * @param kids the Kids profile section.
 * @param deleteConfirmation the delete question ("Delete Alice? …"), while it is asked.
 * @param error why saving or deleting failed.
 */
data class ProfileEditorUiState(
    val creating: Boolean,
    val title: String,
    val loaded: Boolean,
    val name: String,
    val nameError: String?,
    val initial: String,
    val avatar: ProfileAvatar,
    val avatars: List<AvatarChoice>,
    val hasPin: Boolean,
    val pinStatus: String,
    val pinSetup: PinSetupUiState?,
    val kids: KidsSectionUiState,
    val canDelete: Boolean,
    val deleteConfirmation: String?,
    val canSave: Boolean,
    val saving: Boolean,
    val error: String?,
)

/** Where the profile editor goes; called on the main thread. */
interface ProfileEditorHost {
    /** Saved or cancelled: close the editor. */
    fun closeEditor()

    /** [profileId] was deleted (here, or on another device while the editor was open): close the editor. */
    fun profileDeleted(profileId: String)
}

/**
 * Creates a profile (through `AppGraph.createProfile`, which gives it the default addons) or edits one: name, avatar
 * ([ProfileAvatars]), setting, changing or removing its PIN, making a new profile a kids profile ([KidsSectionUiState]:
 * the highest rating it shows, unrated titles, the Home rows it starts with, the titles a parent allowed or blocked),
 * and deleting it after a confirmation. Changes apply on [save]. The caller has checked the profile's PIN before
 * opening the editor.
 *
 * A kids profile needs another profile that isn't one with a PIN: leaving a kids profile asks for such a PIN, so the
 * switch stays off until one exists, and a new kids profile isn't saved without one. Only a new profile becomes a
 * kids profile: an existing one keeps its library and history. The profile's fields (kids, Home rows) are saved
 * through [profiles], its rating settings (`kids.*`) through [settings]; both sync.
 *
 * Call everything on the main thread; [release] ends it.
 */
class ProfileEditorStore internal constructor(
    private val profileId: String?,
    private val profiles: ProfileRepository,
    /** The profile's synced settings: a kids profile's highest rating, unrated titles, allowed and blocked titles. */
    private val settings: SettingsRepository,
    private val createProfile: suspend (name: String, avatar: String?, kids: Boolean, homeCatalogs: List<HomeCatalog>) -> Profile,
    /** The profile's installed addons, whose catalogs a kids profile can be given. */
    private val addons: suspend (profileId: String) -> List<Descriptor>,
    /** Starts the age database's download (`AgeRatings.prefetch`), as a parent turns the Kids profile switch on. */
    private val fetchAgeRatings: () -> Unit,
    private val host: ProfileEditorHost,
    parentScope: CoroutineScope,
) {
    private sealed interface PinChange {
        data object Keep : PinChange
        data object Remove : PinChange
        class Set(val pin: String) : PinChange
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** The profile as it is now (another device's change included). */
    private var original: Profile? = null

    /** The profile as the form loaded it: [save] writes only the fields edited since. */
    private var loadedProfile: Profile? = null

    /** The profile [save] created, once it did: a save that failed after it goes on with it, not with another one. */
    private var created: Profile? = null
    private var name = ""
    private var avatar: ProfileAvatar = ProfileAvatars.all.first()

    /** The user picked [avatar]: an edit writes it only then, so one this build doesn't know stays stored as it is. */
    private var avatarPicked = false
    private var pinChange: PinChange = PinChange.Keep
    private var pinSetup: PinSetupStep? = null
    private var firstPin = ""
    private var digits = ""
    private var pinError: String? = null
    private var confirmingDelete = false
    private var saveAttempted = false
    private var saving = false
    private var error: String? = null
    private var loaded = profileId == null
    private var done = false
    private var deleting = false

    /** Every other profile, once read: whether one of them can let a kids profile be left. */
    private var others: List<Profile>? = null

    /** The addons whose catalogs the Kids profile section lists: a new profile's are the default ones. */
    private var candidates: List<Descriptor> = if (profileId == null) DefaultAddons.descriptors else emptyList()
    private var kids = false
    private var homeCatalogs: List<HomeCatalog> = emptyList()

    /** The rating settings as the profile had them when it loaded (a new profile: the defaults); [save] writes changes. */
    private var storedCap: AgeStep = ProfileSetting.KidsCap.default
    private var storedAllowUnrated: Boolean = ProfileSetting.KidsAllowUnrated.default
    private var cap: AgeStep = storedCap
    private var allowUnrated: Boolean = storedAllowUnrated
    private var allowed: List<KidsTitle> = emptyList()
    private var blocked: List<KidsTitle> = emptyList()

    /** Keys removed from [allowed] and [blocked]: [save] takes only them out, so a title added elsewhere meanwhile stays. */
    private val removedAllowed = HashSet<String>()
    private val removedBlocked = HashSet<String>()

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<ProfileEditorUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            profiles.observeProfiles().collect { all ->
                others = all.filter { it.id != profileId }
                publish()
            }
        }
        if (profileId == null) {
            scope.launch {
                avatar = ProfileAvatars.unused(profiles.profiles().map { it.avatar })
                publish()
            }
        } else {
            scope.launch {
                candidates = readingAddons(TAG) { addons(profileId) } ?: emptyList()
                profiles.observeProfile(profileId).collect { profile ->
                    when {
                        done || deleting -> Unit
                        profile == null -> {
                            done = true
                            host.profileDeleted(profileId)
                        }
                        !loaded -> {
                            original = profile
                            loadedProfile = profile
                            name = profile.name
                            avatar = ProfileAvatars.forProfile(profile.avatar, profile.id)
                            kids = profile.kids
                            homeCatalogs = profile.homeCatalogs
                            val stored = settings.settings(profileId)
                            storedCap = stored.kidsCap
                            storedAllowUnrated = stored.kidsAllowUnrated
                            cap = storedCap
                            allowUnrated = storedAllowUnrated
                            allowed = stored.kidsAllowed
                            blocked = stored.kidsBlocked
                            loaded = true
                            publish()
                        }
                        else -> {
                            original = profile
                            publish()
                        }
                    }
                }
            }
        }
    }

    fun setName(text: String) {
        name = text
        error = null
        publish()
    }

    fun selectAvatar(id: String) {
        avatar = ProfileAvatars.all.firstOrNull { it.id == id } ?: return
        avatarPicked = true
        publish()
    }

    // ---- PIN --------------------------------------------------------------------------------------------------------

    /** "Set a PIN" / "Change PIN": opens the pad. */
    fun startPinSetup() {
        pinSetup = PinSetupStep.ENTER
        firstPin = ""
        digits = ""
        pinError = null
        publish()
    }

    fun pinDigit(digit: Int) {
        if (digit !in 0..9 || pinSetup == null) return
        digits += digit.toString()
        pinError = null
        if (digits.length == ProfilePickerStore.PIN_LENGTH) pinComplete() else publish()
    }

    fun pinBackspace() {
        if (pinSetup == null || digits.isEmpty()) return
        digits = digits.dropLast(1)
        publish()
    }

    fun cancelPinSetup() {
        pinSetup = null
        firstPin = ""
        digits = ""
        pinError = null
        publish()
    }

    /** "Remove PIN" (applies on [save]). */
    fun removePin() {
        pinChange = if (profileId == null) PinChange.Keep else PinChange.Remove
        cancelPinSetup()
    }

    // ---- Kids profile -----------------------------------------------------------------------------------------------

    /**
     * The Kids profile switch (applies on [save]). Turning it on is refused while the profile can't be one
     * ([KidsSectionUiState.blockedReason]); with no Home rows picked yet, it picks the
     * family rows ([HomeCatalog.family]). Turning it on starts the age database's download ([fetchAgeRatings]), so the
     * new kids profile's first titles don't wait for it.
     */
    fun setKids(enabled: Boolean) {
        if (enabled == kids || !loaded || (enabled && !canBecomeKids())) return
        kids = enabled
        if (enabled) {
            fetchAgeRatings()
            if (homeCatalogs.isEmpty()) homeCatalogs = HomeCatalog.family
        }
        publish()
    }

    /** The highest suggested age the kids profile shows (applies on [save]); [AgeStep.ADULT] counts as [AgeStep.TEEN]. */
    fun setKidsCap(step: AgeStep) {
        if (!kids) return
        cap = ProfileSetting.KidsCap.normalize(step)
        publish()
    }

    /** "Allow unrated titles": titles with no suggested age (applies on [save]). */
    fun setAllowUnrated(allow: Boolean) {
        if (!kids) return
        allowUnrated = allow
        publish()
    }

    /**
     * Adds or removes the Home row at [index] of [KidsSectionUiState.homeCatalogs] on the kids profile (applies on
     * [save]); every row may go.
     */
    fun setHomeCatalog(index: Int, selected: Boolean) {
        if (!kids) return
        val catalog = catalogChoices().getOrNull(index)?.first ?: return
        homeCatalogs = if (selected) (homeCatalogs + catalog).distinct() else homeCatalogs - catalog
        publish()
    }

    /** Takes [key] off the allowed titles ([KidsSectionUiState.allowed]; applies on [save]). */
    fun removeAllowed(key: String) {
        if (!kids || allowed.none { it.key == key }) return
        removedAllowed += key
        publish()
    }

    /** Takes [key] off the blocked titles ([KidsSectionUiState.blocked]; applies on [save]). */
    fun removeBlocked(key: String) {
        if (!kids || blocked.none { it.key == key }) return
        removedBlocked += key
        publish()
    }

    // ---- Save, delete, cancel ---------------------------------------------------------------------------------------

    fun save() {
        if (saving || !loaded || done) return
        saveAttempted = true
        val trimmed = name.trim()
        if (nameProblem(trimmed) != null || kidsProblem()) {
            publish()
            return
        }
        saving = true
        error = null
        publish()
        scope.launch {
            try {
                val id = profileId ?: created?.id
                if (id == null) {
                    val profile = createProfile(trimmed, avatar.id, kids, if (kids) homeCatalogs else emptyList())
                    created = profile
                    (pinChange as? PinChange.Set)?.let { profiles.setPin(profile.id, it.pin) }
                    if (kids) saveKidsSettings(profile.id)
                } else {
                    // Against the form's starting point, so another device's change to a field left alone stays.
                    val before = loadedProfile ?: created
                    if (before?.name != trimmed) profiles.rename(id, trimmed)
                    if (avatarPicked && before?.avatar != avatar.id) profiles.setAvatar(id, avatar.id)
                    when (val change = pinChange) {
                        PinChange.Keep -> Unit
                        PinChange.Remove -> profiles.setPin(id, null)
                        is PinChange.Set -> profiles.setPin(id, change.pin)
                    }
                    if (before?.kids != kids || before?.homeCatalogs != homeCatalogs) profiles.setKids(id, kids, homeCatalogs)
                    if (kids) saveKidsSettings(id)
                }
                done = true
                host.closeEditor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "Saving the profile failed" }
                error = T.profiles.editor.saveFailed
            } finally {
                saving = false
                publish()
            }
        }
    }

    fun cancel() {
        done = true
        host.closeEditor()
    }

    /** "Delete profile": asks first ([ProfileEditorUiState.deleteConfirmation]). */
    fun requestDelete() {
        if (profileId == null || !loaded) return
        confirmingDelete = true
        publish()
    }

    fun cancelDelete() {
        confirmingDelete = false
        publish()
    }

    /** Deletes the profile and everything it owns, on every device. */
    fun confirmDelete() {
        val id = profileId ?: return
        if (!confirmingDelete || saving) return
        saving = true
        deleting = true
        publish()
        scope.launch {
            try {
                profiles.delete(id)
                done = true
                host.profileDeleted(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "Deleting the profile failed" }
                error = T.profiles.editor.saveFailed
            } finally {
                saving = false
                deleting = false
                confirmingDelete = false
                publish()
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    // ---- Internals --------------------------------------------------------------------------------------------------

    private fun pinComplete() {
        when (pinSetup) {
            PinSetupStep.ENTER -> {
                firstPin = digits
                digits = ""
                pinSetup = PinSetupStep.CONFIRM
            }
            PinSetupStep.CONFIRM -> if (digits == firstPin) {
                pinChange = PinChange.Set(digits)
                pinSetup = null
                firstPin = ""
                digits = ""
            } else {
                pinSetup = PinSetupStep.ENTER
                firstPin = ""
                digits = ""
                pinError = T.profiles.editor.pinMismatch
            }
            null -> Unit
        }
        publish()
    }

    private fun nameProblem(trimmed: String): String? = when {
        trimmed.length > MAX_NAME_LENGTH -> T.profiles.editor.nameTooLong(MAX_NAME_LENGTH)
        trimmed.isEmpty() -> T.profiles.editor.nameRequired
        else -> null
    }

    private fun hasPin(): Boolean = when (pinChange) {
        PinChange.Keep -> original?.hasPin == true
        PinChange.Remove -> false
        is PinChange.Set -> true
    }

    /** Why this profile can't be a kids profile; null when it can (or while the other profiles aren't read). */
    private fun kidsBlockedReason(): String? = when {
        profileId != null && original?.kids != true -> T.profiles.editor.kids.newOnly
        others?.none { !it.kids && it.hasPin } == true -> T.profiles.editor.kids.needsParent
        else -> null
    }

    private fun canBecomeKids(): Boolean = others != null && kidsBlockedReason() == null

    /** A new kids profile nothing could let a child leave: not saved. */
    private fun kidsProblem(): Boolean = kids && profileId == null && !canBecomeKids()

    /** Writes the rating settings that changed, and takes the removed titles out of the lists as they are stored now. */
    private suspend fun saveKidsSettings(id: String) {
        if (cap != storedCap) settings.set(id, ProfileSetting.KidsCap, cap)
        if (allowUnrated != storedAllowUnrated) settings.set(id, ProfileSetting.KidsAllowUnrated, allowUnrated)
        if (removedAllowed.isNotEmpty()) settings.update(id, ProfileSetting.KidsAllowed) { titles -> titles.filter { it.key !in removedAllowed } }
        if (removedBlocked.isNotEmpty()) settings.update(id, ProfileSetting.KidsBlocked) { titles -> titles.filter { it.key !in removedBlocked } }
    }

    /**
     * The Kids profile section's Home rows ([KidsSectionUiState.homeCatalogs]), each with its row: the family ones, every
     * catalog Home shows from [candidates] (those without required extras), then picked ones neither names.
     */
    private fun catalogChoices(): List<Pair<HomeCatalog, KidsCatalogChoice>> {
        val text = T.profiles.editor.kids
        val listed = LinkedHashMap<HomeCatalog, String>()
        // HomeCatalog.family lists the movies, then the series.
        HomeCatalog.family.zip(listOf(text.familyMovies, text.familySeries)).forEach { (catalog, title) -> listed[catalog] = title }
        for (planned in AggrRequest.board().plan(candidates)) {
            val path = planned.request.path
            val catalog = HomeCatalog(planned.addon.transportUrl, path.type, path.id, path.extra)
            if (catalog !in listed) listed[catalog] = planned.catalog()?.let(::catalogRowTitle) ?: rowTitle(path.id, path.type)
        }
        for (catalog in homeCatalogs) {
            if (catalog !in listed) listed[catalog] = rowTitle(catalog.id, catalog.type)
        }
        return listed.map { (catalog, title) ->
            val addon = candidates.firstOrNull { it.transportUrl == catalog.addonUrl }
            catalog to KidsCatalogChoice(title, addon?.manifest?.name ?: text.addonMissing, catalog in homeCatalogs)
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): ProfileEditorUiState {
        val trimmed = name.trim()
        val problem = nameProblem(trimmed)
        val hasPin = hasPin()
        return ProfileEditorUiState(
            creating = profileId == null,
            title = if (profileId == null) T.profiles.editor.newTitle else T.profiles.editor.editTitle,
            loaded = loaded,
            name = name,
            nameError = problem.takeIf { saveAttempted || trimmed.length > MAX_NAME_LENGTH },
            initial = profileInitial(trimmed),
            avatar = avatar,
            avatars = ProfileAvatars.all.map { AvatarChoice(it, it.id == avatar.id) },
            hasPin = hasPin,
            pinStatus = if (hasPin) T.profiles.editor.pinOn else T.profiles.editor.pinOff,
            pinSetup = pinSetup?.let { step ->
                PinSetupUiState(
                    step = step,
                    title = if (step == PinSetupStep.ENTER) T.profiles.editor.pinEnter else T.profiles.editor.pinConfirm,
                    entered = digits.length,
                    length = ProfilePickerStore.PIN_LENGTH,
                    error = pinError,
                )
            },
            kids = kidsSection(),
            canDelete = profileId != null && loaded,
            deleteConfirmation = if (confirmingDelete) T.profiles.editor.deleteConfirm(isolate(original?.name ?: trimmed)) else null,
            canSave = loaded && problem == null && !kidsProblem() && !saving,
            saving = saving,
            error = error,
        )
    }

    private fun kidsSection(): KidsSectionUiState {
        val text = T.profiles.editor.kids
        return KidsSectionUiState(
            enabled = kids,
            canSwitch = loaded && (kids || canBecomeKids()),
            blockedReason = kidsBlockedReason(),
            caps = if (kids) {
                listOf(
                    KidsCapChoice(AgeStep.LITTLE, text.capLittle, text.capLittleAges, cap == AgeStep.LITTLE),
                    KidsCapChoice(AgeStep.KIDS, text.capKids, text.capKidsAges, cap == AgeStep.KIDS),
                    KidsCapChoice(AgeStep.TEEN, text.capTeen, text.capTeenAges, cap == AgeStep.TEEN),
                )
            } else {
                emptyList()
            },
            allowUnrated = allowUnrated,
            homeCatalogs = if (kids) catalogChoices().map { it.second } else emptyList(),
            allowed = if (kids) titleRows(allowed, removedAllowed) else emptyList(),
            blocked = if (kids) titleRows(blocked, removedBlocked) else emptyList(),
        )
    }

    private fun titleRows(titles: List<KidsTitle>, removed: Set<String>): List<KidsTitleRow> =
        titles.filter { it.key !in removed }.map { KidsTitleRow(it.key, it.name, typeLabel(it.type), T.profiles.editor.kids.remove(isolate(it.name))) }

    companion object {
        /** Its name on the store probe while its screen shows (test reports read it there). */
        const val KEY: String = "profileEditor"

        /** Longest profile name, so tiles stay readable. */
        const val MAX_NAME_LENGTH: Int = 32
        private const val TAG = "ProfileEditor"
    }
}
