package com.anifree.app

import android.content.Context
import android.os.SystemClock
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local parental gate. A production multi-device account must enforce this on its backend too. */
class ParentalPin(context: Context) {
    private val prefs = context.getSharedPreferences("parental_pin", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private var failures = 0
    private var blockedUntil = 0L
    val configured: Boolean get() = prefs.contains("hash")

    private fun digest(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun valid(pin: String) = pin.length in 4..6 && pin.all { it in '0'..'9' }
    fun setup(pin: String): Boolean {
        if (configured || !valid(pin)) return false
        val salt = ByteArray(16).also(random::nextBytes)
        prefs.edit().putString("salt", Base64.getEncoder().encodeToString(salt))
            .putString("hash", Base64.getEncoder().encodeToString(digest(pin, salt))).apply()
        return true
    }
    fun verify(pin: String): Boolean {
        if (!configured || !valid(pin) || SystemClock.elapsedRealtime() < blockedUntil) return false
        val salt = Base64.getDecoder().decode(prefs.getString("salt", ""))
        val stored = Base64.getDecoder().decode(prefs.getString("hash", ""))
        val ok = java.security.MessageDigest.isEqual(stored, digest(pin, salt))
        if (ok) { failures = 0; blockedUntil = 0L }
        else { failures++; if (failures >= 5) { blockedUntil = SystemClock.elapsedRealtime() + 60_000L; failures = 0 } }
        return ok
    }
    fun lockedOut(): Boolean = SystemClock.elapsedRealtime() < blockedUntil
}
