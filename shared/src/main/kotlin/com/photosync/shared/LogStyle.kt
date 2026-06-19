package com.photosync.shared

/**
 * Shared log-line colour classifier so the client card, hub card, and web dashboard all colour
 * the same process types identically. Returns a "#RRGGBB" hex string for a given log line.
 *
 * Priority (first match wins): errors -> restore -> video -> image/WebP -> upload/backup ->
 * maintenance/date-fix -> success -> default. Errors always win so a failed step is never hidden,
 * and a typed success line (e.g. "checkmark WebP done") keeps its process colour rather than going green.
 */
object LogStyle {

    // Aurora design system colours
    const val RED    = "#ff7a78"  // cat_alert_neon  — errors / failures
    const val GREEN  = "#2ee6a6"  // cat_safe_neon   — generic success
    const val BLUE   = "#5b9dff"  // cat_share_neon  — upload / backup transfer
    const val CYAN   = "#22d3ee"  // cat_sync_neon   — image / WebP compression
    const val PURPLE = "#a98bff"  // cat_smart_neon  — video / AI / date repair
    const val AMBER  = "#ffc44d"  // cat_pending_neon — restore from hub
    const val YELLOW = "#a3e635"  // cat_free_neon   — maintenance: cleanup, dedup
    const val GREY   = "#8693a6"  // ink-400         — default / informational

    fun colorFor(line: String): String {
        val s = line.lowercase()

        // 1. Errors / failures — always highest priority
        if ("✗" in line || "error" in s || "fail" in s || "unreachable" in s ||
            "timed out" in s || "timeout" in s || "unauthorized" in s || "missing" in s)
            return RED

        // 2. Restore from hub
        if ("↺" in line || "restore" in s || "restored" in s) return AMBER

        // 3. Video work
        if ("▶" in line || "videospace" in s || "videodaterepair" in s ||
            "poster" in s || "transcod" in s) return PURPLE

        // 4. Image / WebP compression
        if ("◇" in line || "webp" in s || "compress" in s) return CYAN

        // 5. Upload / backup transfer
        if ("⬆" in line || "⬇" in line || "uploading" in s || "saved to usb" in s ||
            "synced" in s || "syncing" in s || "handshake" in s || "download" in s) return BLUE

        // 6. Maintenance / date fixes
        if ("localfix" in s || "date" in s || "exif" in s || "reorg" in s ||
            "dedup" in s || "cleanup" in s || "repair" in s || "manifest" in s) return YELLOW

        // 7. Generic success
        if ("✓" in line || "complete" in s || "done" in s || "ready" in s) return GREEN

        return GREY
    }
}
