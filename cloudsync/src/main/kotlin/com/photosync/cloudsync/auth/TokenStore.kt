package com.photosync.cloudsync.auth

import android.content.Context

/**
 * Persists OAuth tokens per provider ("google" / "onedrive") in SharedPreferences.
 * Stores the refresh token (long-lived) and the current access token + its expiry.
 */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("cloud_tokens", Context.MODE_PRIVATE)

    fun save(provider: String, accessToken: String, refreshToken: String?, expiresInSec: Long) {
        prefs.edit().apply {
            putString("$provider.access", accessToken)
            // Only overwrite the refresh token when a new one is supplied (refresh responses
            // sometimes omit it — keep the old one).
            if (!refreshToken.isNullOrBlank()) putString("$provider.refresh", refreshToken)
            putLong("$provider.expiry", System.currentTimeMillis() + (expiresInSec - 60) * 1000L)
        }.apply()
    }

    /** A non-expired access token, or null if missing/expired (caller should refresh). */
    fun validAccessToken(provider: String): String? {
        val tok = prefs.getString("$provider.access", null) ?: return null
        return if (System.currentTimeMillis() < prefs.getLong("$provider.expiry", 0L)) tok else null
    }

    fun refreshToken(provider: String): String? = prefs.getString("$provider.refresh", null)

    /** Authenticated means we at least hold a refresh token to mint access tokens from. */
    fun isAuthed(provider: String): Boolean = !prefs.getString("$provider.refresh", null).isNullOrBlank()

    fun clear(provider: String) {
        prefs.edit()
            .remove("$provider.access").remove("$provider.refresh").remove("$provider.expiry")
            .apply()
    }
}
