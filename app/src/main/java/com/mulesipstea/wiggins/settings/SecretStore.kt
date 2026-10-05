package com.mulesipstea.wiggins.settings

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts secrets with a Tink AEAD whose keyset is wrapped by a master key in
 * Android Keystore. Ciphertexts are stored as Base64 in DataStore; the
 * secret's name is the associated data, so a value can't be swapped between
 * names.
 */
class SecretStore(context: Context) {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun encrypt(name: String, plaintext: String): String =
        Base64.encodeToString(aead.encrypt(plaintext.toByteArray(), name.toByteArray()), Base64.NO_WRAP)

    fun decrypt(name: String, ciphertext: String): String =
        String(aead.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP), name.toByteArray()))

    private companion object {
        const val KEYSET_NAME = "wiggins_secrets_keyset"
        const val KEYSET_PREFS = "wiggins_secrets_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://wiggins_secrets_master_key"
    }
}
