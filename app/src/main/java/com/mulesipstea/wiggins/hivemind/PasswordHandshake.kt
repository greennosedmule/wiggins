package com.mulesipstea.wiggins.hivemind

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * HiveMind's password handshake (poorman_handshake PasswordHandShake); see
 * docs/hivemind-protocol.md §5. Each side sends an envelope of
 * `hex(iv || SHA-256(iv || password))[0:48]`, and both derive the session key
 * with PBKDF2-HMAC-SHA256 over the password, salted with the XOR of the IVs.
 */
class PasswordHandshake(private val password: String, private val iv: ByteArray = randomIv()) {
    init {
        require(iv.size == IV_SIZE) { "IV must be $IV_SIZE bytes" }
    }

    val envelope: String = createEnvelope(password, iv)

    /** Whether the hub's envelope was made with the same password. */
    fun verify(serverEnvelope: String): Boolean = envelopeMatches(serverEnvelope, password)

    /** Derives the 32-byte session key. 100k HMAC rounds: call off the main thread. */
    fun deriveKey(serverEnvelope: String): ByteArray {
        val serverIv = serverEnvelope.substring(0, IV_SIZE * 2).hexToByteArray()
        val salt = ByteArray(IV_SIZE) { iv[it] xor serverIv[it] }
        return pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, ITERATIONS, KEY_SIZE)
    }

    companion object {
        const val IV_SIZE = 8
        const val ENVELOPE_LENGTH = 48
        const val ITERATIONS = 100_000
        const val KEY_SIZE = 32

        private fun randomIv() = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

        fun createEnvelope(password: String, iv: ByteArray, length: Int = ENVELOPE_LENGTH): String {
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(iv)
                digest(password.toByteArray(Charsets.UTF_8))
            }
            return (iv + digest).toHexString().substring(0, length)
        }

        /** poorman_handshake match_hsub: case-sensitive, length 48 to 80. */
        fun envelopeMatches(envelope: String, password: String): Boolean {
            if (envelope.length !in ENVELOPE_LENGTH..80) return false
            val iv = runCatching { envelope.substring(0, IV_SIZE * 2).hexToByteArray() }.getOrNull() ?: return false
            val expected = createEnvelope(password, iv, envelope.length)
            return MessageDigest.isEqual(expected.toByteArray(), envelope.toByteArray())
        }

        /** PBKDF2 (RFC 8018) over raw bytes, so no provider decides how the password's chars become bytes. */
        fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            val hLen = mac.macLength
            val out = ByteArray(keyLength)
            var block = 1
            var offset = 0
            while (offset < keyLength) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in t.indices) t[i] = t[i] xor u[i]
                }
                t.copyInto(out, offset, 0, minOf(hLen, keyLength - offset))
                offset += hLen
                block++
            }
            return out
        }
    }
}
