package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HiveMind payload ciphers; see docs/hivemind-protocol.md §9. */
enum class HiveCipher(val wireName: String, val nonceSize: Int) {
    CHACHA20_POLY1305("CHACHA20-POLY1305", 12),

    /** The hub reads the first 16 bytes as the nonce, so AES-GCM uses a 16-byte nonce, not 12. */
    AES_GCM("AES-GCM", 16);

    companion object {
        fun fromWire(name: String?) = entries.firstOrNull { it.wireName == name }
    }
}

/** Text encodings for the encrypted fields. Only the two simple ones are implemented. */
enum class HiveEncoding(val wireName: String) {
    JSON_B64("JSON-B64") {
        override fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        override fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    },
    JSON_HEX("JSON-HEX") {
        override fun encode(bytes: ByteArray): String = bytes.toHexString()
        override fun decode(text: String): ByteArray = text.lowercase().hexToByteArray()
    };

    abstract fun encode(bytes: ByteArray): String
    abstract fun decode(text: String): ByteArray

    companion object {
        fun fromWire(name: String?) = entries.firstOrNull { it.wireName == name }
    }
}

class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals and opens HiveMind's encrypted frames:
 * `{"ciphertext": E(C), "tag": E(T), "nonce": E(N)}`, no associated data.
 */
class FrameCipher(
    private val key: ByteArray,
    val cipher: HiveCipher,
    val encoding: HiveEncoding,
    private val nonces: (Int) -> ByteArray = ::randomNonce,
) {
    fun seal(plaintext: String): String {
        val nonce = nonces(cipher.nonceSize)
        val sealed = newCipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size)
        return JsonObject(
            mapOf(
                "ciphertext" to JsonPrimitive(encoding.encode(ciphertext)),
                "tag" to JsonPrimitive(encoding.encode(tag)),
                "nonce" to JsonPrimitive(encoding.encode(nonce)),
            ),
        ).toString()
    }

    /** Opens an encrypted frame. A frame without `tag` carries it appended to the ciphertext. */
    fun open(frame: JsonObject): String {
        try {
            val ciphertext = encoding.decode(frame.getValue("ciphertext").jsonPrimitive.content)
            val nonce = encoding.decode(frame.getValue("nonce").jsonPrimitive.content)
            val tag = frame["tag"]?.jsonPrimitive?.contentOrNull?.let(encoding::decode) ?: ByteArray(0)
            val plain = newCipher(Cipher.DECRYPT_MODE, nonce).doFinal(ciphertext + tag)
            return String(plain, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw DecryptionException("Frame failed to decrypt (wrong key or tampered)", e)
        } catch (e: IllegalArgumentException) {
            throw DecryptionException("Malformed encrypted frame", e)
        } catch (e: NoSuchElementException) {
            throw DecryptionException("Encrypted frame is missing a field", e)
        }
    }

    fun open(frame: String): String = open(Json.parseToJsonElement(frame) as JsonObject)

    private fun newCipher(mode: Int, nonce: ByteArray): Cipher = when (cipher) {
        HiveCipher.CHACHA20_POLY1305 -> chacha().apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }
        HiveCipher.AES_GCM -> Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        }
    }

    private companion object {
        const val TAG_SIZE = 16
        private val random = SecureRandom()

        fun randomNonce(size: Int) = ByteArray(size).also { random.nextBytes(it) }

        /** Conscrypt (Android) and the JDK name ChaCha20-Poly1305 differently. */
        fun chacha(): Cipher = runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }
    }
}
