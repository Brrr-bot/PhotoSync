package com.photosync.shared

/**
 * Shared log-line colour classifier so the client card, hub card, and web dashboard all colour
 * the same process types identically. Returns a "#RRGGBB" hex string for a given log line.
 *
 * Priority (first match wins): errors → restore → video → image/WebP → upload/backup →
 * maintenance/date-fix → success → default. Errors always win so a failed step is never hidden,
 * and a typed success line (e.g. "✓ WebP done") keeps its process colour rather than going green.
 */
object LogStyle {

    const val RED    = "#ff4444"  // errors / failures
    const val GREEN  = "#00ff88"  // generic success
    const val BLUE   = "#33b5ff"  // upload / backup (phone ⇄ hub transfer)
    const val CYAN   = "#00e5ff"  // image / WebP compression
    const val PURPLE = "#bf00ff"  // video transcode / posterise / date repair
    const val AMBER  = "#ffaa00"  // restore from hub
    const val YELLOW = "#ffd54f"  // maintenance: date fix, EXIF, reorg, dedup, cleanup
    const val GREY   = "#888888"  // default / informational

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
