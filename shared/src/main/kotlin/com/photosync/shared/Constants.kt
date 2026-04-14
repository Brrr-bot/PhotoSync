package com.photosync.shared

object Constants {
    // HTTP server port on client phone
    const val CLIENT_PORT = 8765

    // UDP broadcast port — client announces itself, hub listens
    const val DISCOVERY_PORT = 8766

    // UDP broadcast message prefix
    const val DISCOVERY_PREFIX = "PHOTOSYNC_HERE"

    // Change this to any random string before building — both apps must match
    const val SHARED_SECRET = "PhotoSync_ChangeMe_32CharSecretKey"

    const val PATH_HANDSHAKE  = "/handshake"
    const val PATH_MEDIA_LIST = "/media/list"
    const val PATH_MEDIA_FILE = "/media/file"
    const val PATH_REPLACE    = "/replace"
    const val PATH_FIX_DATES  = "/fix_dates"

    const val PARAM_DATE_TAKEN = "dateTaken"

    const val HEADER_HMAC = "X-PhotoSync-HMAC"
    const val HEADER_TIMESTAMP = "X-PhotoSync-Timestamp"
    const val HEADER_DEVICE = "X-PhotoSync-Device"

    // How many milliseconds a signed request remains valid (replay protection)
    const val HMAC_VALIDITY_MS = 60_000L

    // Handshake HTTP connect + read timeout
    const val HANDSHAKE_TIMEOUT_MS = 3_000L

    // File download HTTP timeout (per file)
    const val FILE_TIMEOUT_MS = 120_000L

    // How often the client broadcasts its presence
    const val ANNOUNCE_INTERVAL_MS = 15_000L

    // Hub HTTP server port — phone POSTs /sync here to trigger a pull
    const val HUB_HTTP_PORT = 8767

    // UDP port hub uses to announce itself; phone listens here
    const val HUB_DISCOVERY_PORT = 8768

    // Hub UDP broadcast prefix
    const val HUB_DISCOVERY_PREFIX = "PHOTOSYNC_HUB_HERE"

    // Hub HTTP endpoint the phone calls to request a sync
    const val PATH_SYNC = "/sync"

    // ── OTA updates ───────────────────────────────────────────────────────────
    // Point this at a JSON file containing {"versionCode": 2, "apkUrl": "https://..."}
    // Host it anywhere reachable: GitHub raw, your own server, a Tailscale URL, etc.
    // Leave empty to disable update checks.
    const val UPDATE_CHECK_URL = ""   // e.g. "https://raw.githubusercontent.com/you/repo/main/version.json"
}
