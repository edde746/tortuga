package com.edde746.tortuga.desktop.windows

import com.edde746.tortuga.desktop.bind
import com.edde746.tortuga.desktop.int
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.file.Path

/** The user's known folders, where Explorer has them (moved by the user, redirected to OneDrive): `SHGetKnownFolderPath`. */
internal object WindowsKnownFolders {
    /** The user's Videos folder (`FOLDERID_Videos`); null when Windows can't tell. */
    fun videos(): Path? = path(FOLDERID_VIDEOS)

    private fun path(id: Guid): Path? = Arena.ofConfined().use { a ->
        val guid = a.allocate(16, 8).also { id.write(it) }
        val out = a.allocate(ADDRESS)
        val result = Api.shGetKnownFolderPath.int(guid, 0, MemorySegment.NULL, out)
        val text = out.get(ADDRESS, 0)
        try {
            if (result != S_OK || text == MemorySegment.NULL) null else Path.of(text.reinterpret(Long.MAX_VALUE).getString(0, UTF_16LE))
        } finally {
            // The buffer is the caller's to free, even when the call fails.
            Api.coTaskMemFree.invokeWithArguments(text)
        }
    }

    /** A `GUID` (`KNOWNFOLDERID`): its 32-bit, two 16-bit and eight 8-bit parts, in that order in memory. */
    private class Guid(val data1: Int, val data2: Short, val data3: Short, val data4: ByteArray) {
        fun write(segment: MemorySegment) {
            segment.set(JAVA_INT, 0, data1)
            segment.set(JAVA_SHORT, 4, data2)
            segment.set(JAVA_SHORT, 6, data3)
            data4.forEachIndexed { i, b -> segment.set(JAVA_BYTE, 8L + i, b) }
        }
    }

    /** {18989B1D-99B5-455B-841C-AB7C74E4DDFC} */
    private val FOLDERID_VIDEOS = Guid(
        0x18989B1D, 0x99B5.toShort(), 0x455B, byteArrayOf(0x84.toByte(), 0x1C, 0xAB.toByte(), 0x7C, 0x74, 0xE4.toByte(), 0xDD.toByte(), 0xFC.toByte()),
    )

    private const val S_OK = 0

    /** shell32's and ole32's functions, bound on first use. */
    private object Api {
        private val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
        private val ole32 = SymbolLookup.libraryLookup("ole32", Arena.global())

        val shGetKnownFolderPath = shell32.bind("SHGetKnownFolderPath", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
        val coTaskMemFree = ole32.bind("CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS))
    }
}
