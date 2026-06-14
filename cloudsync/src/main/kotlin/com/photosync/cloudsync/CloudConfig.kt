package com.photosync.cloudsync

/**
 * Credentials and constants for CloudSync.
 *
 * ── PLUG IN TOMORROW ──────────────────────────────────────────────────────────
 * GOOGLE DRIVE: Google Cloud Console → APIs & Services → Credentials → Create
 *   OAuth client ID → application type "TVs and Limited Input devices".
 *   Enable the "Google Drive API". Add yourself as a test user on the consent screen.
 *   Paste the client id + client secret below.
 *
 * ONEDRIVE: Azure Portal → App registrations → New registration →
 *   "Personal Microsoft accounts only" (or "…and work"). Under Authentication →
 *   "Allow public client flows" = Yes. Under API permissions add Microsoft Graph
 *   delegated "Files.Read" and "offline_access". Paste the Application (client) id below.
 *
 * Nothing else needs changing — the device-code flow shows a code on the tablet that
 * you type into any browser once; the refresh token is then stored and reused.
 */
object CloudConfig {

    // ===== fill these in =====
    const val GOOGLE_CLIENT_ID     = ""   // e.g. "1234-abcd.apps.googleusercontent.com"
    const val GOOGLE_CLIENT_SECRET = ""   // device-flow client secret
    const val MS_CLIENT_ID         = ""   // Azure application (client) id
    const val MS_TENANT            = "consumers"  // "consumers" = personal OneDrive, "common" = personal+work
    // =========================

    // ── Google OAuth2 device flow + Drive API (stable endpoints) ──
    const val GOOGLE_DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    const val GOOGLE_TOKEN_URL       = "https://oauth2.googleapis.com/token"
    const val GOOGLE_SCOPE           = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_FILES_URL        = "https://www.googleapis.com/drive/v3/files"

    // ── Microsoft OAuth2 device flow + Graph API ──
    val MS_DEVICE_CODE_URL: String get() = "https://login.microsoftonline.com/$MS_TENANT/oauth2/v2.0/devicecode"
    val MS_TOKEN_URL: String       get() = "https://login.microsoftonline.com/$MS_TENANT/oauth2/v2.0/token"
    const val MS_SCOPE             = "Files.Read offline_access"
    const val GRAPH_DELTA_URL      = "https://graph.microsoft.com/v1.0/me/drive/root/delta"

    // ── Compression ──
    const val WEBP_QUALITY = 72

    // ── Local HTTP server the phone pulls compressed copies + recalls originals from ──
    const val HTTP_PORT = 8770

    fun googleConfigured() = GOOGLE_CLIENT_ID.isNotBlank() && GOOGLE_CLIENT_SECRET.isNotBlank()
    fun msConfigured()     = MS_CLIENT_ID.isNotBlank()
}
