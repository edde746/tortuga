package com.edde746.tortuga.desktop

import com.edde746.tortuga.desktop.linux.FileChooserPortal
import com.edde746.tortuga.desktop.linux.inFlatpak
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.ui.app.SaveResult
import java.awt.EventQueue
import java.awt.Window
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

private const val TAG = "Files"

/*
 * The desktop's file dialogs: a modal JFileChooser over the window, built in the system's look as the JDK names it:
 * Windows' file dialog style on Windows, GTK's on GNOME, and Swing's own Metal look on any other Linux desktop, KDE
 * included; the app's look and feel is left as it was. Inside the Flatpak the desktop portal's dialogs instead
 * (FileChooserPortal): a JFileChooser there sees only the sandbox, whose home folder is in memory and gone when the app
 * exits. Call each on the AWT event thread (Compose's main thread). None logs a path or a file's text.
 */

/**
 * The folder picker of Settings → Downloads' folder over [parent], titled [title], opened at [initial] where that
 * folder exists: [chosen] gets the picked folder's absolute path, nothing when the user cancels or the portal fails
 * (logged).
 */
internal fun chooseDirectory(parent: Window, title: String, initial: String?, chosen: (String) -> Unit) {
    if (inFlatpak) {
        val window = FileChooserPortal.parentWindow(parent)
        return throughPortal({ FileChooserPortal.open(window, title, directory = true, folder = initial) }) { folder -> folder?.let { chosen(it.absolutePath) } }
    }
    val chooser = inSystemLook { JFileChooser() }
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    initial?.let(::File)?.takeIf { it.isDirectory }?.let { folder ->
        chooser.currentDirectory = folder.parentFile ?: folder
        chooser.selectedFile = folder
    }
    if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return
    chooser.selectedFile?.absolutePath?.let(chosen)
}

/**
 * The save dialog of Settings → Export and import and of Settings → Home's Export collections
 * (`TortugaPlatform.saveFile`) over [parent], [fileName] proposed: the chooser lists JSON files in its default folder
 * (Documents on Windows, the home folder on Linux), and a name typed there without an extension gets `.json`; the
 * portal's dialog starts where the desktop chooses, and its file is written under the name the user gave it (the only
 * one the document portal shares). [text] is written as UTF-8 on a thread of its own, then [done] runs on the AWT event
 * thread; FAILED also when the portal fails.
 */
internal fun saveTextFile(parent: Window, fileName: String, text: String, done: (SaveResult) -> Unit) {
    if (inFlatpak) {
        val window = FileChooserPortal.parentWindow(parent)
        val title = UIManager.getString("FileChooser.saveDialogTitleText").orEmpty()
        return throughPortal({ FileChooserPortal.save(window, title, fileName) }, failed = { done(SaveResult.FAILED) }) { file ->
            if (file == null) done(SaveResult.CANCELLED) else write(file, text, done)
        }
    }
    val chooser = inSystemLook { JFileChooser() }
    chooser.fileFilter = FileNameExtensionFilter("JSON", "json")
    chooser.selectedFile = File(chooser.currentDirectory, fileName)
    if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return done(SaveResult.CANCELLED)
    val chosen = chooser.selectedFile
    write(if (chosen.extension.isEmpty()) File(chosen.path + ".json") else chosen, text, done)
}

/**
 * The open dialog (`TortugaPlatform.openFile`: Settings → Export and import, Settings → Home's Import collections, the
 * player's shader import) over [parent], every file listed (one saved under another type is still offered). The chosen
 * file is read on a thread of its own and decoded strictly as UTF-8, then its name and text go to [opened], or [failed]
 * runs when it can't be read, isn't UTF-8 or the portal failed, on the AWT event thread; nothing when the user cancels.
 */
internal fun openTextFile(parent: Window, opened: (name: String, text: String) -> Unit, failed: () -> Unit) {
    if (inFlatpak) {
        val window = FileChooserPortal.parentWindow(parent)
        val title = UIManager.getString("FileChooser.openDialogTitleText").orEmpty()
        return throughPortal({ FileChooserPortal.open(window, title, directory = false, folder = null) }, failed) { file ->
            file?.let { read(it, opened, failed) }
        }
    }
    val chooser = inSystemLook { JFileChooser() }
    if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return
    read(chooser.selectedFile, opened, failed)
}

/** Writes [text] into [file] as UTF-8 on a thread of its own, then [done] on the AWT event thread. */
private fun write(file: File, text: String, done: (SaveResult) -> Unit) {
    thread(name = "Files.save", isDaemon = true) {
        val saved = try {
            file.writeText(text)
            Log.i(TAG) { "Saved a file of ${text.length} characters" }
            true
        } catch (e: IOException) {
            Log.w(TAG) { "The file couldn't be written: ${e.javaClass.simpleName}" }
            false
        }
        EventQueue.invokeLater { done(if (saved) SaveResult.SAVED else SaveResult.FAILED) }
    }
}

/** Reads [file] on a thread of its own, strictly as UTF-8: its name and text to [opened], else [failed], on the AWT event thread. */
private fun read(file: File, opened: (name: String, text: String) -> Unit, failed: () -> Unit) {
    thread(name = "Files.open", isDaemon = true) {
        // A CharacterCodingException (not UTF-8) is an IOException.
        val text = try {
            file.readBytes().decodeToString(throwOnInvalidSequence = true)
        } catch (e: IOException) {
            Log.w(TAG) { "The file couldn't be read: ${e.javaClass.simpleName}" }
            null
        }
        EventQueue.invokeLater { if (text != null) opened(file.name, text) else failed() }
    }
}

/**
 * Runs the portal's dialog [show] on a thread of its own (it blocks until the user answers): what the user chose (null
 * when cancelled) goes to [answered], or [failed] runs when the portal couldn't show it (logged), on the AWT event
 * thread.
 */
private fun throughPortal(show: () -> File?, failed: () -> Unit = {}, answered: (File?) -> Unit) {
    thread(name = "Files.portal", isDaemon = true) {
        val result = try {
            Result.success(show())
        } catch (e: IOException) {
            Log.w(TAG) { "The file dialog couldn't be shown: ${e.message}" }
            Result.failure(e)
        }
        EventQueue.invokeLater { result.fold(answered) { failed() } }
    }
}

/** [create]'s components get the system's look and feel; the one in effect before is restored afterwards. */
private fun <T> inSystemLook(create: () -> T): T {
    val previous = UIManager.getLookAndFeel()
    val switched = runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }.isSuccess
    try {
        return create()
    } finally {
        if (switched && previous != null) runCatching { UIManager.setLookAndFeel(previous) }
    }
}
