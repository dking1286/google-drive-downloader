package dev.dking.googledrivedownloader.cli

/**
 * Command definitions for Picocli.
 */
sealed class Command {
  /**
   * Authenticate with Google Drive using OAuth 2.0.
   */
  data class Auth(val force: Boolean = false) : Command()

  /**
   * Synchronize files from Google Drive.
   */
  data class Sync(
    val full: Boolean = false,
    val dryRun: Boolean = false,
    val includePattern: String? = null,
    val excludePattern: String? = null,
    val maxSize: Long? = null,
  ) : Command()

  /**
   * Show current sync status and statistics.
   */
  object Status : Command()

  /**
   * Clear local state and start fresh.
   */
  object Reset : Command()
}
