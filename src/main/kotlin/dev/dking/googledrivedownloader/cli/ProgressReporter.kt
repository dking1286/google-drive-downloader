package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.SyncStatusSnapshot
import java.io.PrintStream

/**
 * Progress reporter for displaying sync events to the user.
 * Supports ANSI colors and progress bars when running in a TTY.
 */
class ProgressReporter(
  private val output: PrintStream = System.out,
  private val isTty: Boolean = System.console() != null,
  private val verbose: Boolean = false,
  private val quiet: Boolean = false,
) {
  private var lastProgressLine: String? = null

  companion object {
    // ANSI color codes
    private const val RESET = "\u001B[0m"
    private const val GREEN = "\u001B[32m"
    private const val RED = "\u001B[31m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val CYAN = "\u001B[36m"
    private const val BOLD = "\u001B[1m"

    // Progress bar characters
    private const val PROGRESS_BAR_WIDTH = 30
    private const val PROGRESS_FILL = '█'
    private const val PROGRESS_EMPTY = '░'
  }

  /**
   * Report a single sync event to the user.
   * Handles formatting and display logic for each event type.
   */
  fun report(event: SyncEvent) {
    if (quiet &&
      event !is SyncEvent.Failed &&
      event !is SyncEvent.Completed &&
      event !is SyncEvent.FileFailed
    ) {
      return
    }

    when (event) {
      is SyncEvent.Started -> {
        if (!quiet) {
          println(cyan("Sync started at ${event.timestamp}"))
        }
      }
      is SyncEvent.DiscoveringFiles -> {
        if (!quiet) {
          println("Discovered ${bold(event.filesFound.toString())} files...")
        }
      }
      is SyncEvent.FileQueued -> {
        if (verbose) {
          println("Queued: ${event.name}")
        }
      }
      is SyncEvent.FileDownloading -> {
        if (isTty) {
          updateProgressBar(event)
        } else if (verbose) {
          printDownloadProgress(event)
        }
      }
      is SyncEvent.FileCompleted -> {
        clearProgressLine()
        println("${green("✓")} ${event.name}")
      }
      is SyncEvent.FileFailed -> {
        clearProgressLine()
        println("${red("✗")} ${event.name}: ${event.error}")
      }
      is SyncEvent.Progress -> {
        if (!quiet && !isTty) {
          println(
            "Progress: ${event.filesProcessed}/${event.totalFiles} files, " +
              formatBytes(event.bytesDownloaded),
          )
        }
      }
      is SyncEvent.Completed -> {
        clearProgressLine()
        println()
        println(green(bold("Sync completed:")))
        println("  Files: ${event.filesProcessed}")
        println("  Size: ${formatBytes(event.bytesDownloaded)}")
        if (event.failedFiles > 0) {
          println("  ${red("Failed: ${event.failedFiles}")}")
        } else {
          println("  Failed: 0")
        }
        println("  Duration: ${event.duration}")
      }
      is SyncEvent.Failed -> {
        clearProgressLine()
        println(red(bold("Sync failed: ${event.error}")))
      }
    }
  }

  /**
   * Display current sync status.
   */
  fun displayStatus(status: SyncStatusSnapshot) {
    println(bold("Sync Status"))
    println("─".repeat(40))
    println("Last sync: ${status.lastSyncTime ?: yellow("Never")}")
    println("Files tracked: ${status.filesTracked}")
    println("Total size: ${formatBytes(status.totalSize)}")
    println(
      "Pending: ${if (status.pendingFiles > 0) yellow(status.pendingFiles.toString()) else "0"}",
    )
    val errorsText =
      if (status.failedFiles > 0) red(status.failedFiles.toString()) else "0"
    println("Errors: $errorsText")
  }

  /**
   * Print a message to output.
   */
  fun println(message: String = "") {
    output.println(message)
  }

  /**
   * Print a message without newline.
   */
  fun print(message: String) {
    output.print(message)
  }

  /**
   * Print an error message.
   */
  fun printError(message: String) {
    output.println(red("Error: $message"))
  }

  private fun updateProgressBar(event: SyncEvent.FileDownloading) {
    val progress =
      if (event.totalBytes != null && event.totalBytes > 0) {
        (event.bytesDownloaded.toDouble() / event.totalBytes * 100).toInt()
      } else {
        -1
      }

    val progressBar =
      if (progress >= 0) {
        val filled = (progress * PROGRESS_BAR_WIDTH / 100).coerceIn(0, PROGRESS_BAR_WIDTH)
        val empty = PROGRESS_BAR_WIDTH - filled
        "[${PROGRESS_FILL.toString().repeat(filled)}${PROGRESS_EMPTY.toString().repeat(empty)}]"
      } else {
        ""
      }

    val progressText =
      if (event.totalBytes != null) {
        "${formatBytes(event.bytesDownloaded)} / ${formatBytes(event.totalBytes)}"
      } else {
        formatBytes(event.bytesDownloaded)
      }

    val line =
      if (progress >= 0) {
        "\r${cyan("⬇")} ${truncateName(event.name, 30)} $progressBar $progress% $progressText"
      } else {
        "\r${cyan("⬇")} ${truncateName(event.name, 30)} $progressText"
      }

    // Clear previous line and print new one
    output.print("\r" + " ".repeat(lastProgressLine?.length ?: 0))
    output.print(line)
    output.flush()
    lastProgressLine = line
  }

  private fun printDownloadProgress(event: SyncEvent.FileDownloading) {
    val progress =
      if (event.totalBytes != null && event.totalBytes > 0) {
        "${event.bytesDownloaded * 100 / event.totalBytes}%"
      } else {
        formatBytes(event.bytesDownloaded)
      }
    output.println("Downloading ${event.name}: $progress")
  }

  private fun clearProgressLine() {
    if (isTty && lastProgressLine != null) {
      output.print("\r" + " ".repeat(lastProgressLine!!.length) + "\r")
      output.flush()
      lastProgressLine = null
    }
  }

  private fun truncateName(
    name: String,
    maxLength: Int,
  ): String {
    return if (name.length <= maxLength) {
      name.padEnd(maxLength)
    } else {
      "..." + name.takeLast(maxLength - 3)
    }
  }

  // ANSI color helpers
  private fun green(text: String): String = if (isTty) "$GREEN$text$RESET" else text

  private fun red(text: String): String = if (isTty) "$RED$text$RESET" else text

  private fun yellow(text: String): String = if (isTty) "$YELLOW$text$RESET" else text

  private fun cyan(text: String): String = if (isTty) "$CYAN$text$RESET" else text

  private fun bold(text: String): String = if (isTty) "$BOLD$text$RESET" else text

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
