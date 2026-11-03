package com.edde746.tortuga.presentation.collections

/**
 * Settings → Home → Import collections ([CollectionImportStore]): a collections file (Nuvio's format, as Nuvio and
 * AIOMetadata write it) by its link ([CollectionImportStore.fetch]) or, where the platform has file dialogs, from a
 * file the platform opens and hands over as text ([CollectionImportStore.open]); then what importing it would do, and
 * [CollectionImportStore.confirm] imports it.
 *
 * @param title "Import collections"; [note] one line on what it reads.
 * @param urlLabel the link field's label, [urlHint] its placeholder; [fetchLabel] its button ("Get").
 * @param openFileLabel "Open file"; null where the platform has no file dialogs.
 * @param step where the import stands; the link field and Open file are enabled unless [CollectionImportStep.Loading].
 */
data class CollectionImportUiState(
    val title: String,
    val note: String,
    val urlLabel: String,
    val urlHint: String,
    val fetchLabel: String,
    val openFileLabel: String?,
    val step: CollectionImportStep,
)

sealed interface CollectionImportStep {
    /** Nothing asked yet. */
    data object Idle : CollectionImportStep

    /** The link is being read, or the file checked. */
    data object Loading : CollectionImportStep

    /** [message]: why the link or the file can't be imported ("This isn’t a collections file: …"). */
    data class Failed(val message: String) : CollectionImportStep

    /**
     * What importing would do: [collections] one row each, then [lines] (what is added and replaced, what the sources
     * need, what is left out and why). [canImport] while something would be imported; [importLabel] "Import".
     */
    data class Summary(
        val collections: List<CollectionImportRow>,
        val lines: List<String>,
        val importLabel: String,
        val canImport: Boolean,
    ) : CollectionImportStep

    /** Imported: [message] ("Added 2 collections"), [doneLabel] "Done" closes the page. */
    data class Done(val message: String, val doneLabel: String) : CollectionImportStep
}

/**
 * A collection the file would import: [title] and [detail] ("New · 4 folders", "Replaces yours · 2 folders"), then
 * [folders], its first folders' titles ("Ember Pictures, Tide Studio, …"); [replaces] the profile has a collection
 * with its id.
 */
data class CollectionImportRow(val title: String, val detail: String, val folders: String, val replaces: Boolean)
