package com.uniq.airplay.util

import android.content.Context
import java.security.SecureRandom

/**
 * Stable pseudo-MAC used in AirPlay's mDNS `deviceid` TXT record (SPEC §5B.2).
 * Real hardware MACs are restricted on Android 11+ (and TV boxes often
 * expose spoofed ones), so we persist a random 6-byte identity for the
 * life of the install.
 */
object DeviceIdentity {
    private const val PREFS = "com.uniq.airplay.identity"
    private const val KEY_MAC = "airplay_mac"

    fun stableMac(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_MAC, null)?.let { return it }
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        val hex = mac.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        prefs.edit().putString(KEY_MAC, hex).apply()
        return hex
    }
}
