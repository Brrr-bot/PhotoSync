package com.photosync.cloudsync.sync

import android.content.Context

/** Remembers which cloud files have already been compressed, so re-runs skip them. */
class SyncState(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private val done = prefs.getStringSet("done", emptySet())!!.toMutableSet()

    fun isDone(provider: String, id: String) = "$provider:$id" in done
    fun markDone(provider: String, id: String) { done.add("$provider:$id") }
    fun count() = done.size
    fun flush() { prefs.edit().putStringSet("done", done.toSet()).apply() }
}
