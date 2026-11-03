package com.edde746.tortuga.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.edde746.tortuga.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] on Android. Each value is sealed with AES-256-GCM (the key name as associated data, so an entry can't
 * be moved to another name) under one key held in the Android Keystore: non-exportable, and usable without user
 * authentication or an unlocked device, so background sync can read it. The ciphertext lives in the app-private
 * SharedPreferences file [PREFERENCES_NAME] (`shared_prefs/com.edde746.tortuga.secrets.xml`).
 *
 * Keystore keys are never backed up or transferred, and the app excludes the preferences file from cloud backup and
 * device transfer. Should ciphertext still outlive its key (a restore that ignored the rules, a wiped Keystore, an OS
 * bug), the store drops what it can no longer open and reports those entries as missing; the next [put] creates a
 * new key. Only two things count as that: the Keystore has no key ([KeyStore.getKey] returns null), or an entry fails
 * its authentication tag under the key (or is malformed: that entry alone goes). Any other Keystore failure throws
 * from [get] and [put] and keeps every entry for a later attempt: keystore2 reports its transient failures (a system
 * error, a binder call that failed) as `UnrecoverableKeyException`, the same as a key it can't use, and
 * [SessionVault][com.edde746.tortuga.sync.account.SessionVault] reports them as a failure of the store.
 */
class AndroidSecretStore internal constructor(
    context: Context,
    private val preferencesName: String,
    private val keyAlias: String,
) : SecretStore {
    constructor(context: Context) : this(context, PREFERENCES_NAME, KEY_ALIAS)

    private val context: Context = context.applicationContext ?: context
    private val preferences: SharedPreferences by lazy { this.context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE) }

    override suspend fun get(key: String): ByteArray? = locked {
        val entry = preferences.getString(key, null) ?: return@locked null
        val secretKey = loadKey() ?: run {
            // Ciphertext without its key can never be opened again.
            Log.w(TAG) { "The Keystore key is gone; dropping ${preferences.all.size} unreadable entries" }
            resetKey()
            return@locked null
        }
        try {
            open(secretKey, key, entry)
        } catch (e: AEADBadTagException) {
            // Sealed under an earlier key, or damaged: it will never open.
            Log.w(TAG, e) { "Dropping '$key': it doesn't open under the current key" }
            remove(key)
            null
        } catch (e: MalformedEntryException) {
            Log.w(TAG, e) { "Dropping '$key': malformed entry" }
            remove(key)
            null
        }
    }

    override suspend fun put(key: String, value: ByteArray) = locked {
        val entry = seal(loadKey() ?: generateKey(), key, value)
        check(preferences.edit().putString(key, entry).commit()) { "Couldn't write $preferencesName" }
    }

    override suspend fun delete(key: String) = locked { remove(key) }

    private fun remove(key: String) {
        if (preferences.contains(key)) check(preferences.edit().remove(key).commit()) { "Couldn't write $preferencesName" }
    }

    /** Deletes the Keystore key and every entry sealed under it. */
    private fun resetKey() {
        try {
            keyStore().deleteEntry(keyAlias)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, e) { "Couldn't delete the Keystore key" }
        }
        check(preferences.edit().clear().commit()) { "Couldn't write $preferencesName" }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun loadKey(): SecretKey? = keyStore().getKey(keyAlias, null) as SecretKey?

    private fun generateKey(): SecretKey {
        // A new key can't open anything sealed before it.
        check(preferences.edit().clear().commit()) { "Couldn't write $preferencesName" }
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /** `version ‖ IV ‖ ciphertext ‖ tag`, Base64. The Keystore picks the IV (randomized encryption). */
    private fun seal(secretKey: SecretKey, key: String, value: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(key.encodeToByteArray())
        val sealed = cipher.doFinal(value)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV size ${iv.size}" }
        return Base64.encodeToString(byteArrayOf(FORMAT_VERSION) + iv + sealed, Base64.NO_WRAP)
    }

    private fun open(secretKey: SecretKey, key: String, entry: String): ByteArray {
        val bytes = try {
            Base64.decode(entry, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw MalformedEntryException("not Base64")
        }
        if (bytes.size < 1 + IV_BYTES + TAG_BITS / 8 || bytes[0] != FORMAT_VERSION) throw MalformedEntryException("unknown format")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES))
        cipher.updateAAD(key.encodeToByteArray())
        return cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private class MalformedEntryException(message: String) : Exception(message)

    companion object {
        /** The SharedPreferences file (`shared_prefs/<name>.xml`) the app excludes from backup and device transfer. */
        const val PREFERENCES_NAME = "com.edde746.tortuga.secrets"

        private const val KEY_ALIAS = "com.edde746.tortuga.secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
        private const val TAG = "SecretStore"

        /** One lock for every instance: they share the preferences file and the Keystore key. */
        private val lock = Mutex()
    }
}
