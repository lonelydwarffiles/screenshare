package com.screenshare

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption keyed with PBKDF2(password, salt).
 *
 * Used to encrypt WebRTC signaling payloads (SDP, ICE candidates) before they
 * are relayed through the signaling server.  The server therefore sees only
 * opaque ciphertext and cannot read or tamper with the WebRTC negotiation.
 *
 * Wire format — iv and ct are base64url-encoded without padding, sent as
 * additional top-level fields on each signaling message:
 *   { "type": "offer",         "iv": "…", "ct": "…" }
 *   { "type": "answer",        "iv": "…", "ct": "…" }
 *   { "type": "ice_candidate", "iv": "…", "ct": "…" }
 *
 * ct is the GCM-authenticated ciphertext of the inner payload JSON:
 *   offer/answer  → { "sdp": "…" }
 *   ice_candidate → { "sdpMid": "…", "sdpMLineIndex": 0, "candidate": "…" }
 *
 * Encryption is only applied when a session password is set.  Sessions without
 * a password fall back to plain signaling — the media stream is always protected
 * by WebRTC's built-in DTLS-SRTP regardless.
 */
class SessionCrypto(password: String, salt: ByteArray) {

    private val secretKey: SecretKey = run {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec    = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    data class EncryptedPayload(val iv: String, val ct: String)

    /** Encrypt [plaintext] and return base64url-encoded iv + ciphertext+tag. */
    fun encrypt(plaintext: String): EncryptedPayload {
        val iv     = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            iv = Base64.encodeToString(iv,         FLAGS),
            ct = Base64.encodeToString(ciphertext, FLAGS),
        )
    }

    /**
     * Decrypt an [EncryptedPayload] and return the original plaintext string.
     * Throws [javax.crypto.AEADBadTagException] if authentication fails.
     */
    fun decrypt(payload: EncryptedPayload): String {
        val iv         = Base64.decode(payload.iv, FLAGS)
        val ciphertext = Base64.decode(payload.ct, FLAGS)
        val cipher     = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val ALGORITHM         = "AES/GCM/NoPadding"
        private const val KEY_BITS          = 256
        private const val GCM_IV_BYTES      = 12
        private const val GCM_TAG_BITS      = 128
        private const val PBKDF2_ITERATIONS = 100_000
        private val       FLAGS             = Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING

        /** Generate a random 16-byte salt for a new session. */
        fun generateSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        /** Encode salt bytes as a lowercase hex string for JSON transport. */
        fun saltToHex(salt: ByteArray): String =
            salt.joinToString("") { "%02x".format(it) }

        /** Decode a hex salt string back to bytes. */
        fun saltFromHex(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
