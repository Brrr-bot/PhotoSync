package com.photosync.shared.crypto

import android.util.Base64
import com.photosync.shared.Constants
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacAuth {

    private const val ALGORITHM = "HmacSHA256"

    /**
     * Signs a payload string. Payload convention: "$timestamp:$deviceName"
     * Returns a Base64-encoded HMAC-SHA256 signature.
     */
    fun sign(payload: String, secret: String = Constants.SHARED_SECRET): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Verifies a signature. Returns false if the signature is wrong or the
     * timestamp is outside the validity window.
     */
    fun verify(
        payload: String,
        signature: String,
        secret: String = Constants.SHARED_SECRET,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        val expected = sign(payload, secret)
        if (!constantTimeEquals(expected, signature)) return false

        // Extract timestamp from payload ("timestamp:deviceName")
        val ts = payload.substringBefore(':').toLongOrNull() ?: return false
        return Math.abs(System.currentTimeMillis() - ts) < Constants.HMAC_VALIDITY_MS
    }

    /** Build the standard payload string used across all endpoints. */
    fun buildPayload(timestampMs: Long, deviceName: String): String = "$timestampMs:$deviceName"

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
