package com.meta.portal.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The camera's per-device identity: a non-exportable EC P-256 key pair held in
 * the Android Keystore. The private key never leaves the secure store; the
 * camera proves who it is by signing a server-issued nonce (challenge-response),
 * so there's no shared token to leak and the server only ever holds the public
 * key. The matching verification lives in signaling-server/auth.js.
 */
class CameraIdentity {

    private val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(): PrivateKey {
        (keystore.getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
        return keystore.getKey(ALIAS, null) as PrivateKey
    }

    /** Public key as X.509 SubjectPublicKeyInfo (SPKI) DER, base64 (server format). */
    fun publicKeyBase64(): String {
        ensureKey()
        val pub = keystore.getCertificate(ALIAS).publicKey
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    /** Sign the base64 nonce; returns the DER ECDSA signature, base64. */
    fun signNonce(nonceB64: String): String {
        val key = ensureKey()
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(nonce)
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
    }

    /**
     * Sign an arbitrary UTF-8 string (e.g. a canonical HTTP request line) with
     * the device key; returns the DER ECDSA signature, base64. Used to
     * authenticate REST calls without any shared secret. Verified server-side by
     * verifyCameraData() in signaling-server/auth.js.
     */
    fun signData(data: String): String {
        val key = ensureKey()
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "portal_camera_key"
    }
}
