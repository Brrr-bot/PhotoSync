package com.photosync.shared.model

data class DashboardStatusResponse(
    val hubReady: Boolean,
    val batteryOptimizedIgnored: Boolean,
    val accessibilityEnabled: Boolean,
    val currentMode: String,
    val progressCurrent: Int,
    val progressTotal: Int,
    val currentFile: String,
    val compressionCurrent: Int,
    val compressionTotal: Int,
    val recentLogs: List<String>,
    val lastSyncSummary: String,
    val updatedAt: Long
)
