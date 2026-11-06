package app.ivynote.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.ivynote.core.CredentialStore
import app.ivynote.core.IvyJson
import app.ivynote.core.StoredAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A library's sign-in (server, token, cached user), encrypted with an AES key
 * that lives in the Android Keystore and never leaves it (guide 8.2). The
 * ciphertext sits in private shared preferences, excluded from backups.
 */
class KeystoreCredentialStore(context: Context, private val account: String) : CredentialStore {
    private val prefs = context.getSharedPreferences("ivy.credentials", Context.MODE_PRIVATE)

    override fun load(): StoredAccount? {
        val stored = prefs.getString(account, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
            IvyJson.decodeFromString(StoredAccount.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            // A key lost with a restored or reset device: sign in again.
            Log.w("ivy", "sign-in unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun save(account: StoredAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(IvyJson.encodeToString(StoredAccount.serializer(), account).encodeToByteArray())
        if (!prefs.edit().putString(this.account, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            error("could not save the sign-in")
        }
    }

    override fun clear() {
        prefs.edit().remove(account).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "ivynote.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
