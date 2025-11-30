package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.SyncStatusSnapshot

/**
 * Progress reporter for displaying sync events to the user.
 */
class ProgressReporter {
  /**
   * Report a single sync event to the user.
   * Handles formatting and display logic for each event type.
   */
  fun report(event: SyncEvent) {
    when (event) {
      is SyncEvent.Started -> println("Sync started at ${event.timestamp}")
      is SyncEvent.DiscoveringFiles -> println("Discovered ${event.filesFound} files...")
      is SyncEvent.FileQueued -> println("Queued: ${event.name}")
      is SyncEvent.FileDownloading -> {
        // Display progress bar or percentage
        val progress =
          if (event.totalBytes != null) {
            "${event.bytesDownloaded * 100 / event.totalBytes}%"
          } else {
            "${event.bytesDownloaded} bytes"
          }
        println("Downloading ${event.name}: $progress")
      }
      is SyncEvent.FileCompleted -> println("✓ ${event.name}")
      is SyncEvent.FileFailed -> println("✗ ${event.name}: ${event.error}")
      is SyncEvent.Progress -> {
        // Update overall progress indicator
        println(
          "Progress: ${event.filesProcessed}/${event.totalFiles} files, ${formatBytes(
            event.bytesDownloaded,
          )}",
        )
      }
      is SyncEvent.Completed -> {
        println("\nSync completed:")
        println("  Files: ${event.filesProcessed}")
        println("  Size: ${formatBytes(event.bytesDownloaded)}")
        println("  Failed: ${event.failedFiles}")
        println("  Duration: ${event.duration}")
      }
      is SyncEvent.Failed -> println("Sync failed: ${event.error}")
    }
  }

  /**
   * Display current sync status.
   */
  fun displayStatus(status: SyncStatusSnapshot) {
    println("Last sync: ${status.lastSyncTime ?: "Never"}")
    println("Files tracked: ${status.filesTracked}")
    println("Total size: ${formatBytes(status.totalSize)}")
    println("Pending: ${status.pendingFiles}")
    println("Errors: ${status.failedFiles}")
  }

  private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
      size /= 1024
      unitIndex++
    }

    return "%.2f %s".format(size, units[unitIndex])
  }
}
