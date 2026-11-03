package com.edde746.tortuga.desktop.secrets

import com.edde746.tortuga.desktop.bind
import com.edde746.tortuga.secrets.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * [SecretStore] on Linux: the freedesktop Secret Service (GNOME Keyring, KWallet, KeePassXC) through libsecret, one
 * item per key in the default collection, with schema [SCHEMA] and the key as its `key` attribute. The service keeps
 * the items encrypted and unlocks the collection with the user's login.
 *
 * libsecret is loaded on first use. Without it, or without a Secret Service on the D-Bus session bus, calls fail with
 * an [IllegalStateException] that says so.
 */
internal class LibsecretStore : SecretStore {
    private val schema: MemorySegment by lazy { api.schema(SCHEMA) }

    override suspend fun get(key: String): ByteArray? = call { arena ->
        val error = arena.allocate(ADDRESS)
        val value = api.lookup.invokeWithArguments(schema, MemorySegment.NULL, error, arena.utf8(ATTRIBUTE), arena.utf8(key), MemorySegment.NULL) as MemorySegment
        api.check("lookup", error)
        if (value.address() == 0L) return@call null
        try {
            val length = arena.allocate(JAVA_LONG)
            val bytes = api.valueGet.invokeWithArguments(value, length) as MemorySegment
            val size = length.get(JAVA_LONG, 0)
            if (size == 0L) ByteArray(0) else bytes.reinterpret(size).toArray(JAVA_BYTE)
        } finally {
            api.valueUnref.invokeWithArguments(value)
        }
    }

    override suspend fun put(key: String, value: ByteArray) = call { arena ->
        // A zero-length value still needs a valid pointer.
        val data = arena.allocate(maxOf(value.size, 1).toLong()).copyFrom(MemorySegment.ofArray(value))
        val secret = api.valueNew.invokeWithArguments(data, value.size.toLong(), arena.utf8(CONTENT_TYPE)) as MemorySegment
        try {
            val error = arena.allocate(ADDRESS)
            api.store.invokeWithArguments(
                schema, MemorySegment.NULL, arena.utf8("Tortuga: $key"), secret, MemorySegment.NULL, error,
                arena.utf8(ATTRIBUTE), arena.utf8(key), MemorySegment.NULL,
            )
            api.check("store", error)
        } finally {
            api.valueUnref.invokeWithArguments(secret)
        }
    }

    override suspend fun delete(key: String) = call { arena ->
        val error = arena.allocate(ADDRESS)
        // FALSE with no error means there was nothing to remove.
        api.clear.invokeWithArguments(schema, MemorySegment.NULL, error, arena.utf8(ATTRIBUTE), arena.utf8(key), MemorySegment.NULL)
        api.check("clear", error)
    }

    /** Runs [block] off the caller's thread, one call at a time. */
    private suspend fun <T> call(block: (Arena) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { Arena.ofConfined().use(block) }
    }

    companion object {
        /** The libsecret schema name of every item (its `xdg:schema` attribute). */
        const val SCHEMA = "com.edde746.tortuga.Secret"

        private const val ATTRIBUTE = "key"
        private const val CONTENT_TYPE = "application/octet-stream"

        private val lock = Mutex()

        // Retried on the next call if loading fails, so every call reports the same clear error.
        private val api by lazy { Api() }

        private fun Arena.utf8(text: String): MemorySegment = allocateFrom(text)
    }

    /** The libsecret and GLib functions. */
    private class Api {
        private val libsecret = load("libsecret-1.so.0")
        private val glib = load("libglib-2.0.so.0")

        // The variadic tails are the attributes: name, value, …, NULL.
        private val schemaNew = libsecret.bind("secret_schema_new", ADDRESS, listOf(ADDRESS, JAVA_INT), listOf(ADDRESS, JAVA_INT, ADDRESS))
        // The two *_binary_sync functions need libsecret 0.19 or newer: an older one fails here, with that hint.
        val store = libsecret.bind(
            "secret_password_store_binary_sync", JAVA_INT,
            listOf(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS), listOf(ADDRESS, ADDRESS, ADDRESS),
        )
        val lookup = libsecret.bind("secret_password_lookup_binary_sync", ADDRESS, listOf(ADDRESS, ADDRESS, ADDRESS), listOf(ADDRESS, ADDRESS, ADDRESS))
        val clear = libsecret.bind("secret_password_clear_sync", JAVA_INT, listOf(ADDRESS, ADDRESS, ADDRESS), listOf(ADDRESS, ADDRESS, ADDRESS))
        val valueNew = libsecret.bind("secret_value_new", ADDRESS, listOf(ADDRESS, JAVA_LONG, ADDRESS))
        val valueGet = libsecret.bind("secret_value_get", ADDRESS, listOf(ADDRESS, ADDRESS))
        val valueUnref = libsecret.bind("secret_value_unref", null, listOf(ADDRESS))
        private val errorFree = glib.bind("g_error_free", null, listOf(ADDRESS))
        private val quarkToString = glib.bind("g_quark_to_string", ADDRESS, listOf(JAVA_INT))

        /** A schema with one string attribute, `key`. Kept for the life of the process. */
        fun schema(name: String): MemorySegment {
            val arena = Arena.global()
            // SECRET_SCHEMA_NONE; one SECRET_SCHEMA_ATTRIBUTE_STRING attribute.
            val schema = schemaNew.invokeWithArguments(arena.allocateFrom(name), 0, arena.allocateFrom(ATTRIBUTE), 0, MemorySegment.NULL) as MemorySegment
            check(schema.address() != 0L) { "secret_schema_new failed" }
            return schema
        }

        /** Throws if the call set its `GError **` ([slot]). */
        fun check(operation: String, slot: MemorySegment) {
            val error = slot.get(ADDRESS, 0)
            if (error.address() == 0L) return
            // GError: GQuark domain; gint code; gchar *message.
            val gError = error.reinterpret(16)
            val domain = (quarkToString.invokeWithArguments(gError.get(JAVA_INT, 0)) as MemorySegment).text()
            val message = gError.get(ADDRESS, 8).text()
            errorFree.invokeWithArguments(error)
            // D-Bus and I/O errors: no session bus, no provider, or a provider without a usable default collection.
            if (domain == "g-dbus-error-quark" || domain == "g-io-error-quark") {
                throw IllegalStateException(
                    "No usable Secret Service (org.freedesktop.secrets) on the D-Bus session bus: GNOME Keyring, KWallet or " +
                        "KeePassXC must be running with a default collection. libsecret $operation: $message",
                )
            }
            throw IllegalStateException("libsecret $operation failed ($domain): $message")
        }

        private fun MemorySegment.text(): String = if (address() == 0L) "" else reinterpret(Long.MAX_VALUE).getString(0)

        private fun load(library: String): SymbolLookup = try {
            SymbolLookup.libraryLookup(library, Arena.global())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$library is missing: install libsecret (libsecret-1-0 on Debian and Ubuntu)", e)
        }

        private fun SymbolLookup.bind(
            name: String,
            result: MemoryLayout?,
            fixed: List<MemoryLayout>,
            variadic: List<MemoryLayout> = emptyList(),
        ): MethodHandle {
            val arguments = (fixed + variadic).toTypedArray()
            val descriptor = if (result == null) FunctionDescriptor.ofVoid(*arguments) else FunctionDescriptor.of(result, *arguments)
            val options = if (variadic.isEmpty()) emptyArray() else arrayOf(Linker.Option.firstVariadicArg(fixed.size))
            return try {
                bind(name, descriptor, *options)
            } catch (e: NoSuchElementException) {
                throw IllegalStateException("$name is missing: libsecret 0.19 or newer is needed", e)
            }
        }
    }
}
