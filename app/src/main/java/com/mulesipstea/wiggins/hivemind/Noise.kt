package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * HiveMind protocol v3: the Noise handshake and its transport (HIVEMIND-CRYPTO-1 §3.4),
 * as hivemind-bus-client 1.x `noise.py` and poorman-handshake 2.x implement it.
 * See docs/hivemind-protocol.md §16. Only what Wiggins uses: the initiator side of
 * Noise_XXpsk2_25519_ChaChaPoly_SHA256. KKpsk0 is never chosen (XX keeps working once
 * the hub has pinned our key), and AES-GCM is never chosen when ChaChaPoly is offered,
 * which every hub must do.
 */

class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the Noise handshake needs from the phone. */
interface NoiseKeys {
    /** This phone's static X25519 private key; the hub pins its public half at the first handshake. */
    val staticKey: ByteArray

    /** The pre-shared key for the hub whose HELLO named [nodeId]. Can run argon2id: slow the first time. */
    fun psk(nodeId: String): ByteArray

    /** The hub's handshake didn't authenticate with the key [psk] returned, so forget any stored copy. */
    fun rejected(nodeId: String) {}
}

/** Keys that last one connection: a new static key, and the PSK derived on every handshake. */
class EphemeralNoiseKeys(private val password: String) : NoiseKeys {
    override val staticKey: ByteArray = Noise.newPrivateKey()
    override fun psk(nodeId: String) = Noise.derivePsk(password, nodeId)
}

/**
 * Keys kept on the phone: a fixed static key, and PSKs stretched once per hub and
 * password. [onDerived] and [onRejected] persist changes to the stored PSKs.
 */
class StoredNoiseKeys(
    override val staticKey: ByteArray,
    private val password: String,
    stored: Map<String, ByteArray>,
    private val onDerived: (nodeId: String, psk: ByteArray) -> Unit,
    private val onRejected: (nodeId: String) -> Unit,
) : NoiseKeys {
    private val psks = stored.toMutableMap()

    override fun psk(nodeId: String): ByteArray =
        psks[nodeId] ?: Noise.derivePsk(password, nodeId).also {
            psks[nodeId] = it
            onDerived(nodeId, it)
        }

    override fun rejected(nodeId: String) {
        psks.remove(nodeId)
        onRejected(nodeId)
    }
}

object Noise {
    const val PATTERN = "XXpsk2"
    const val SUITE = "25519_ChaChaPoly_SHA256"
    const val PROTOCOL_NAME = "Noise_${PATTERN}_$SUITE"
    const val PROTOCOL_VERSION = 3

    /** A Noise message's limit, tag included. */
    const val MAX_MESSAGE = 65_535

    /** Plaintext per transport message, as the Python client chunks (noise.py CHUNK_SIZE). */
    const val CHUNK_SIZE = 65_000

    /** The most a chunked message may add up to before it's dropped (noise.py MAX_REASSEMBLY_BYTES). */
    const val MAX_REASSEMBLY = 32 * 1024 * 1024

    private val random = SecureRandom()

    fun newPrivateKey(): ByteArray = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(random, it) }

    fun publicKey(privateKey: ByteArray): ByteArray =
        ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val shared = ByteArray(X25519.POINT_SIZE)
        // False for a low-order point (all-zero output), which Noise must reject.
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)) throw NoiseException("Hub sent an invalid key")
        return shared
    }

    /**
     * The 32-byte PSK: argon2id over the password (t=3, 64 MiB, p=1), salted with
     * SHA-256 of the hub's node id (poorman_handshake.noise.derive_psk). Takes a
     * second or more on a phone: call off the main thread, and keep the result.
     */
    fun derivePsk(password: String, nodeId: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray(Charsets.UTF_8))
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(64 * 1024)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        return ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }

    /**
     * The prologue both sides bind into the handshake: the hub's cleartext HELLO and
     * HANDSHAKE payloads, then the protocol name (noise.py build_prologue). Tampering
     * with either payload makes the handshake fail.
     */
    fun prologue(hello: JsonObject, shake: JsonObject): ByteArray =
        (canonicalJson(hello) + canonicalJson(shake) + PROTOCOL_NAME).toByteArray(Charsets.UTF_8)

    /**
     * Python's `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`,
     * which the hub uses for the prologue. Numbers keep the text they arrived as,
     * which is what Python wrote.
     */
    fun canonicalJson(element: JsonElement): String = buildString { appendCanonical(element) }

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { i, (key, value) ->
                    if (i > 0) append(',')
                    appendString(key)
                    append(':')
                    appendCanonical(value)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) append(',')
                    appendCanonical(value)
                }
                append(']')
            }
            JsonNull -> append("null")
            is JsonPrimitive -> if (element.isString) appendString(element.content) else append(element.content)
        }
    }

    private fun StringBuilder.appendString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/** A Noise CipherState for ChaChaPoly: a key and a counting nonce. */
internal class NoiseCipher(private val key: ByteArray) {
    private var n = 0L

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, ad).doFinal(plaintext)

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, ad).doFinal(ciphertext)
    } catch (e: GeneralSecurityException) {
        throw NoiseException("Noise message failed to decrypt", e)
    }

    private fun cipher(mode: Int, ad: ByteArray): Cipher {
        // 32 bits of zeros, then the counter as 64-bit little-endian.
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (n ushr (8 * i)).toByte()
        n++
        return chacha().apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (ad.isNotEmpty()) updateAAD(ad)
        }
    }

    private companion object {
        /** Conscrypt (Android) and the JDK name ChaCha20-Poly1305 differently. */
        fun chacha(): Cipher = runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }
            .getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }
    }
}

/** The Noise SymmetricState for SHA-256. */
private class SymmetricState(protocolName: String) {
    var h: ByteArray = protocolName.toByteArray(Charsets.UTF_8).let { if (it.size <= 32) it.copyOf(32) else sha256(it) }
        private set
    private var ck = h.copyOf()
    private var cipher: NoiseCipher? = null

    fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, key) = hkdf(ck, ikm, 2)
        ck = newCk
        cipher = NoiseCipher(key)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, tempH, key) = hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(tempH)
        cipher = NoiseCipher(key)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val out = cipher?.encrypt(h, plaintext) ?: plaintext
        mixHash(out)
        return out
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val out = cipher?.decrypt(h, ciphertext) ?: ciphertext
        mixHash(ciphertext)
        return out
    }

    /** The two transport ciphers: initiator to responder, then responder to initiator. */
    fun split(): Pair<NoiseCipher, NoiseCipher> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return NoiseCipher(k1) to NoiseCipher(k2)
    }

    companion object {
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        /** Noise's HKDF: [outputs] blocks chained from HMAC(ck, ikm). */
        fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val out = mutableListOf<ByteArray>()
            var previous = ByteArray(0)
            for (i in 1..outputs) {
                previous = hmac(temp, previous + byteArrayOf(i.toByte()))
                out += previous
            }
            return out
        }
    }
}

/**
 * The initiator's side of Noise_XXpsk2_25519_ChaChaPoly_SHA256:
 * `-> e`, `<- e, ee, s, es, psk`, `-> s, se`. Use each step once, in order.
 */
class NoiseHandshake(
    private val staticKey: ByteArray,
    private val psk: ByteArray,
    prologue: ByteArray,
    private val ephemeralKey: ByteArray = Noise.newPrivateKey(),
) {
    init {
        require(psk.size == 32) { "PSK must be 32 bytes" }
    }

    private val state = SymmetricState(Noise.PROTOCOL_NAME).apply { mixHash(prologue) }
    private var remoteEphemeral: ByteArray? = null

    /** The hub's static public key, once message 2 is read. */
    var remoteStatic: ByteArray? = null
        private set

    /** Message 1: our ephemeral key, then [payload], already encrypted because of the PSK. */
    fun writeMessage1(payload: ByteArray): ByteArray {
        val e = Noise.publicKey(ephemeralKey)
        state.mixHash(e)
        state.mixKey(e)
        return e + state.encryptAndHash(payload)
    }

    /** Reads message 2 and returns its payload. Fails if the hub's PSK or prologue differ from ours. */
    fun readMessage2(message: ByteArray): ByteArray {
        if (message.size < 32 + 48 + 16) throw NoiseException("Hub's Noise message is too short")
        val re = message.copyOfRange(0, 32)
        remoteEphemeral = re
        state.mixHash(re)
        state.mixKey(re)
        state.mixKey(Noise.dh(ephemeralKey, re))
        val rs = state.decryptAndHash(message.copyOfRange(32, 80))
        remoteStatic = rs
        state.mixKey(Noise.dh(ephemeralKey, rs))
        state.mixKeyAndHash(psk)
        return state.decryptAndHash(message.copyOfRange(80, message.size))
    }

    /** Message 3: our static key, then [payload]. The handshake is done after this. */
    fun writeMessage3(payload: ByteArray): ByteArray {
        val re = checkNotNull(remoteEphemeral) { "message 2 not read" }
        val s = state.encryptAndHash(Noise.publicKey(staticKey))
        state.mixKey(Noise.dh(staticKey, re))
        return s + state.encryptAndHash(payload)
    }

    fun split(chunkSize: Int = Noise.CHUNK_SIZE): NoiseTransport {
        val (send, receive) = state.split()
        return NoiseTransport(send, receive, chunkSize)
    }
}

/**
 * A finished v3 session. Each websocket binary message is one Noise transport
 * message whose first plaintext byte says what it carries (noise.py `_FRAME_*`):
 * a whole JSON or binary HiveMessage, or a chunk of one too big for one Noise message.
 */
class NoiseTransport internal constructor(
    private val sender: NoiseCipher,
    private val receiver: NoiseCipher,
    /** Plaintext per chunk; smaller only in tests. */
    private val chunkSize: Int = Noise.CHUNK_SIZE,
) {
    private var reassembly: java.io.ByteArrayOutputStream? = null
    private var reassemblingJson = false

    /** Encrypts a JSON HiveMessage as one or more websocket binary messages, to send in order. */
    fun seal(json: String): List<ByteArray> {
        val body = json.toByteArray(Charsets.UTF_8)
        if (body.size <= chunkSize) return listOf(encrypt(FRAME_JSON, body, 0, body.size))
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < body.size) {
            val end = minOf(offset + chunkSize, body.size)
            val marker = when {
                offset == 0 -> FRAME_FIRST_JSON
                end == body.size -> FRAME_LAST
                else -> FRAME_MORE
            }
            frames += encrypt(marker, body, offset, end)
            offset = end
        }
        return frames
    }

    private fun encrypt(marker: Byte, body: ByteArray, from: Int, to: Int): ByteArray {
        val plaintext = ByteArray(1 + to - from)
        plaintext[0] = marker
        body.copyInto(plaintext, 1, from, to)
        return sender.encrypt(ByteArray(0), plaintext)
    }

    /**
     * Decrypts one websocket binary message: a whole JSON HiveMessage, or null while a
     * chunked one is still arriving or for a binary (bitstring) one, which Wiggins
     * doesn't ask for. Throws when the session can't continue (a failed decryption
     * puts the nonce counters out of step).
     */
    fun open(frame: ByteArray): String? {
        if (frame.size > Noise.MAX_MESSAGE) throw NoiseException("Hub sent an oversized Noise message")
        val plaintext = receiver.decrypt(ByteArray(0), frame)
        if (plaintext.isEmpty()) throw NoiseException("Hub sent an empty Noise message")
        val body = plaintext.copyOfRange(1, plaintext.size)
        val open = reassembly
        return when (plaintext[0]) {
            FRAME_JSON, FRAME_BINARY -> {
                if (open != null) throw NoiseException("Hub started a message inside a chunked one")
                if (plaintext[0] == FRAME_JSON) body.toString(Charsets.UTF_8) else null
            }
            FRAME_FIRST_JSON, FRAME_FIRST_BINARY -> {
                if (open != null) throw NoiseException("Hub started a message inside a chunked one")
                reassembly = java.io.ByteArrayOutputStream().apply { write(body) }
                reassemblingJson = plaintext[0] == FRAME_FIRST_JSON
                null
            }
            FRAME_MORE, FRAME_LAST -> {
                if (open == null) throw NoiseException("Hub sent a chunk with no message open")
                open.write(body)
                if (open.size() > Noise.MAX_REASSEMBLY) throw NoiseException("Hub's chunked message is too big")
                if (plaintext[0] == FRAME_MORE) {
                    null
                } else {
                    reassembly = null
                    if (reassemblingJson) open.toByteArray().toString(Charsets.UTF_8) else null
                }
            }
            else -> throw NoiseException("Hub sent an unknown Noise frame marker ${plaintext[0]}")
        }
    }

    private companion object {
        const val FRAME_JSON: Byte = 0
        const val FRAME_BINARY: Byte = 1
        const val FRAME_FIRST_JSON: Byte = 2
        const val FRAME_FIRST_BINARY: Byte = 3
        const val FRAME_MORE: Byte = 4
        const val FRAME_LAST: Byte = 5
    }
}
