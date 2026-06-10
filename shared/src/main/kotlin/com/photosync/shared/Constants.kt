package com.photosync.shared

object Constants {
    const val CLIENT_PORT = 8765
    const val DISCOVERY_PORT = 8766
    const val DISCOVERY_PREFIX = "PHOTOSYNC_HERE"
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

    const val HMAC_VALIDITY_MS = 60_000L
    const val HANDSHAKE_TIMEOUT_MS = 12_000L
    const val FILE_TIMEOUT_MS = 120_000L
    const val ANNOUNCE_INTERVAL_MS = 15_000L

    const val HUB_HTTP_PORT = 8767
    const val HUB_DISCOVERY_PORT = 8768
    const val HUB_DISCOVERY_PREFIX = "PHOTOSYNC_HUB_HERE"
    const val PATH_SYNC = "/sync"
    const val PATH_DASHBOARD = "/dashboard"
    const val PATH_LOCATION = "/location"
    const val PATH_HUB_FILES  = "/hub/files"
    const val PATH_HUB_THUMB  = "/hub/thumb"
    const val PATH_HUB_FILE   = "/hub/file"
    const val PATH_HUB_DELETE = "/hub/delete"
    const val PATH_HUB_POSTER = "/hub/poster"
    const val PATH_HUB_META   = "/hub/meta"

    const val UPDATE_PORTAL_URL = "https://app-updates.mcubittbuilders.workers.dev"
}
