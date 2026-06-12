package com.meta.portal.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The owner's app PIN — the single security gate for the whole app. Set once on
 * first use, required on every foreground.
 *
 * The PIN is never stored. We keep a salted PBKDF2-HMAC-SHA256 hash, verify in
 * constant time, and apply a short lockout after repeated failures so a 4-digit
 * space can't be brute-forced by hand.
 */
object PinManager {
    const val LENGTH = 4
    private const val PREFS = "portal_security_pin"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_ITER = "iter"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCK_UNTIL = "lockUntil"
    private const val ITERATIONS = 150_000
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 30_000L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPinSet(c: Context): Boolean = prefs(c).contains(KEY_HASH)

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit()
            .putString(KEY_SALT, b64(salt))
            .putString(KEY_HASH, b64(pbkdf2(pin, salt, ITERATIONS)))
            .putInt(KEY_ITER, ITERATIONS)
            .remove(KEY_FAILS).remove(KEY_LOCK_UNTIL)
            .apply()
    }

    /** Milliseconds remaining on the failure lockout, or 0 if not locked out. */
    fun lockoutRemainingMs(c: Context): Long =
        (prefs(c).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Verify [pin]; on success clears failures, on failure counts toward lockout. */
    fun verify(c: Context, pin: String): Boolean {
        val p = prefs(c)
        if (lockoutRemainingMs(c) > 0) return false
        val salt = p.getString(KEY_SALT, null)?.let { unb64(it) } ?: return false
        val expected = p.getString(KEY_HASH, null)?.let { unb64(it) } ?: return false
        val iter = p.getInt(KEY_ITER, ITERATIONS)
        val ok = constantTimeEquals(pbkdf2(pin, salt, iter), expected)
        if (ok) {
            p.edit().remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
        } else {
            val fails = p.getInt(KEY_FAILS, 0) + 1
            val e = p.edit()
            if (fails >= MAX_FAILS) {
                e.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCKOUT_MS).remove(KEY_FAILS)
            } else {
                e.putInt(KEY_FAILS, fails)
            }
            e.apply()
        }
        return ok
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
