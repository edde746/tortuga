package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow

/**
 * Settings → Home's collection editor ([CollectionEditorStore]): a new collection or one of the profile's, its title,
 * view, All tab and tile shape, and its folders; over it the [folder] page of one folder, and over that the source
 * [picker]. Nothing is stored before [CollectionEditorStore.save]; Back ([CollectionEditorStore.back]) drops what
 * wasn't saved, asking first ([discard]) when something changed.
 * Static labels (Title, Folders, Add folder, Save, Cover image URL, Sources, Add source, …) come from
 * `T.collections.editor`.
 *
 * @param heading "New collection" or "Edit collection".
 * @param loaded the collection being edited is read (always true for a new one); nothing changes before.
 * @param title the collection's title as typed ([CollectionEditorStore.setTitle]).
 * @param viewMode Tabs, Rows or Follow the app ([CollectionEditorStore.choose] with its id).
 * @param showAllTab the All tab before the sources' tabs ([CollectionEditorStore.toggle] with its id).
 * @param shape Poster, Landscape or Square: every folder's tile ([CollectionEditorStore.choose] with its id).
 * @param folders in their order on Home.
 * @param saving the save is under way: nothing changes meanwhile.
 * @param error why the last save was refused ("Give the collection a title.", "“Ember” has no source."); null otherwise.
 * @param folder the folder page while it is open ([CollectionEditorStore.openFolder], [CollectionEditorStore.addFolder]).
 * @param picker the source picker over the folder page while it is open ([CollectionEditorStore.openPicker]).
 * @param discard the question Back asks when the draft changed; null otherwise.
 */
data class CollectionEditorUiState(
    val heading: String,
    val loaded: Boolean,
    val title: String,
    val viewMode: SettingChoiceRow,
    val showAllTab: SettingToggleRow,
    val shape: SettingChoiceRow,
    val folders: List<EditorFolderRow>,
    val saving: Boolean,
    val error: String?,
    val folder: FolderEditorUiState?,
    val picker: SourcePickerUiState?,
    val discard: DiscardQuestion?,
)

/**
 * "Discard changes?": [discardLabel] drops the draft and closes the editor ([CollectionEditorStore.discard]),
 * [keepLabel] ("Keep editing") goes back to it ([CollectionEditorStore.keepEditing], or Back).
 */
data class DiscardQuestion(val title: String, val discardLabel: String, val keepLabel: String)

/**
 * A folder of the collection: pressed, its page opens ([CollectionEditorStore.openFolder]).
 *
 * @param detail its source count: "2 sources".
 * @param moveUpLabel and the other labels name the folder: "Move Ember up", "Reorder Ember", "Delete Ember", "More
 *   actions for Ember" (its menu button).
 */
data class EditorFolderRow(
    val id: String,
    val title: String,
    val detail: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val dragLabel: String,
    val deleteLabel: String,
    val moreLabel: String,
)

/**
 * The page of one folder: its title, cover image, whether the tile shows its title, and its sources.
 *
 * @param heading the folder's title, or "New folder" while it has none.
 * @param coverUrl the cover image's link as typed ([CollectionEditorStore.setCoverUrl]); empty for none.
 * @param hideTitle the tile shows its cover alone ([CollectionEditorStore.toggle] with its id).
 */
data class FolderEditorUiState(
    val heading: String,
    val title: String,
    val coverUrl: String,
    val hideTitle: SettingToggleRow,
    val sources: List<EditorSourceRow>,
)

/**
 * A source of the folder page: "Popular · Movies" from "Cinemeta", "Watchlist" from "Trakt".
 *
 * @param note what it lacks on this profile now ("Needs the addon AIOMetadata", "Connect Trakt"); null when it can show.
 * @param moveUpLabel and the other labels name the source, [moreLabel] its menu button's.
 */
data class EditorSourceRow(
    val title: String,
    val detail: String,
    val note: String?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val dragLabel: String,
    val removeLabel: String,
    val moreLabel: String,
)

/**
 * The source picker ([CollectionEditorStore.pick]): "Addon catalogs", the profile's installed addons' catalogs, then
 * "Trackers", each connected tracker's lists and a list by its link (Trakt's in builds with Trakt, MDBList's while
 * MDBList is connected).
 *
 * @param urlForm the list-by-link form while it is open, over the picker.
 */
data class SourcePickerUiState(val groups: List<SourcePickerGroup>, val urlForm: ListUrlForm?)

/**
 * A group of the picker.
 *
 * @param loading some of its choices are still being read (a tracker's lists).
 * @param notes what couldn't be read ("Trakt’s lists can’t be read now"), or that there is nothing to pick.
 */
data class SourcePickerGroup(val title: String, val choices: List<SourceChoice>, val loading: Boolean, val notes: List<String>)

/**
 * A source to add ([CollectionEditorStore.pick] with its [key]), or, for a list by its link, the form that asks for it.
 *
 * @param detail where it comes from: the addon's name, the tracker's.
 * @param added the folder has it already: picking it again does nothing.
 */
data class SourceChoice(val key: String, val title: String, val detail: String, val added: Boolean)

/**
 * A list by its link ([CollectionEditorStore.submitListUrl]): "A Trakt list by link".
 *
 * @param hint the field's placeholder: "https://trakt.tv/users/…/lists/…".
 * @param resolving the link is being looked up for the list's name.
 * @param error why the link didn't give a list; null otherwise.
 */
data class ListUrlForm(val title: String, val hint: String, val addLabel: String, val resolving: Boolean, val error: String?)
