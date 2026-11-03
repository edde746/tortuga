package com.edde746.tortuga.desktop.windows

import com.edde746.tortuga.desktop.bind
import com.edde746.tortuga.desktop.int
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.charset.StandardCharsets.UTF_16LE

/** String values of the Windows registry (`RegGetValueW`), as installers and players leave their paths there. */
internal object WindowsRegistry {
    /** The predefined keys read from; [handle] is the `HKEY` constant, sign-extended as `winreg.h` defines it. */
    enum class Root(val handle: Long) {
        CLASSES_ROOT(0x80000000.toInt().toLong()),
        CURRENT_USER(0x80000001.toInt().toLong()),
        LOCAL_MACHINE(0x80000002.toInt().toLong()),
    }

    /**
     * The string [value] of [key] under [root] (environment variables in it expanded), or its default value when [value]
     * is empty; null when the key or the value isn't there or isn't a string. [key] is read in this process's (64-bit)
     * view; a 32-bit program's keys are under `SOFTWARE\WOW6432Node`.
     */
    fun string(root: Root, key: String, value: String): String? = runCatching {
        Arena.ofConfined().use { a ->
            val hkey = MemorySegment.ofAddress(root.handle)
            val subKey = a.allocateFrom(key, UTF_16LE)
            val name = a.allocateFrom(value, UTF_16LE)
            val size = a.allocate(JAVA_INT)
            if (Api.regGetValueW.int(hkey, subKey, name, RRF_RT_REG_SZ, MemorySegment.NULL, MemorySegment.NULL, size) != ERROR_SUCCESS) {
                return@use null
            }
            val data = a.allocate(size.get(JAVA_INT, 0).toLong().coerceAtLeast(2))
            if (Api.regGetValueW.int(hkey, subKey, name, RRF_RT_REG_SZ, MemorySegment.NULL, data, size) != ERROR_SUCCESS) {
                return@use null
            }
            data.getString(0, UTF_16LE).takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private const val ERROR_SUCCESS = 0

    /** `REG_SZ` only; a `REG_EXPAND_SZ` value comes expanded as one. */
    private const val RRF_RT_REG_SZ = 0x2

    /** advapi32's function, bound on first use. */
    private object Api {
        private val advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global())

        val regGetValueW = advapi32.bind(
            "RegGetValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
        )
    }
}
