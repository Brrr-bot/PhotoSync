package com.photosync.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Two-level cache for hub thumbnails (in-memory + on-disk), keyed by device/name.
 *
 * Hub files are immutable — a given device/name always has the same thumbnail — so once a
 * thumbnail is cached we never need to re-fetch it. The gallery shows cached thumbnails
 * instantly and only downloads thumbnails for files it hasn't seen before (i.e. newly
 * transferred ones). The disk cache survives across screen opens and app restarts.
 */
object ThumbnailCache {

    private val mem = object : LruCache<String, Bitmap>(80) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    private fun dir(context: Context): File =
        File(context.cacheDir, "hub_thumbs").apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, key: String): File {
        // Stable, filesystem-safe name from the key.
        val safe = Integer.toHexString(key.hashCode()) + "_" +
            key.takeLast(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir(context), "$safe.jpg")
    }

    /** Cached thumbnail (memory → disk), or null if not yet cached. */
    fun get(context: Context, key: String): Bitmap? {
        mem.get(key)?.let { return it }
        val f = fileFor(context, key)
        if (f.exists()) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)?.let { mem.put(key, it); return it }
            } catch (_: Throwable) {}
        }
        return null
    }

    /** Store freshly-fetched thumbnail bytes in both memory and disk. */
    fun put(context: Context, key: String, bytes: ByteArray): Bitmap? {
        try { fileFor(context, key).writeBytes(bytes) } catch (_: Throwable) {}
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { mem.put(key, it) }
        } catch (_: Throwable) { null }
    }

    fun has(context: Context, key: String): Boolean =
        mem.get(key) != null || fileFor(context, key).exists()
}
