package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.api.impl.DriveServiceFactory
import dev.dking.googledrivedownloader.api.impl.GoogleDriveClientImpl
import dev.dking.googledrivedownloader.api.impl.TokenManager
import dev.dking.googledrivedownloader.cli.commands.CommandParser
import dev.dking.googledrivedownloader.cli.commands.ParseResult
import dev.dking.googledrivedownloader.sync.SyncEngine
import dev.dking.googledrivedownloader.sync.SyncEngineConfig
import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.impl.SyncEngineImpl
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for the CLI application.
 */
class CliApplication(
  private val driveClient: GoogleDriveClient,
  private val syncEngine: SyncEngine,
  private val progressReporter: ProgressReporter,
) {
  /**
   * Exit codes for the CLI application.
   */
  object ExitCodes {
    const val SUCCESS = 0
    const val FAILURE = 1
    const val USER_ERROR = 2
  }

  companion object {
    /**
     * Main entry point. Parses arguments, loads configuration, and executes the command.
     *
     * @param args Command-line arguments
     * @param input Reader for user input (for testing)
     * @param output PrintStream for output (for testing)
     * @param error PrintStream for error output (for testing)
     * @return Exit code
     */
    suspend fun main(
      args: Array<String>,
      input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
      output: PrintStream = System.out,
      error: PrintStream = System.err,
    ): Int {
      // Parse command-line arguments
      val parser = CommandParser()
      val parseResult =
        parser.parse(args).getOrElse {
          // If parsing failed, let Picocli handle the error output
          val exitCode = parser.parseAndGetExitCode(args)
          return if (exitCode == 0) ExitCodes.USER_ERROR else exitCode
        }

      // Load configuration
      val configPath = parseResult.configPath ?: AppConfig.defaultConfigPath()
      val appConfig =
        AppConfig.load(configPath).getOrElse { exception ->
          error.println("Error: ${exception.message}")
          return ExitCodes.USER_ERROR
        }

      // Create all services (bootstrapping)
      val tokenPath =
        Path.of(System.getProperty("user.home"))
          .resolve(".google-drive-downloader/tokens.json")
      val downloadDir = Path.of(appConfig.downloadDirectory)

      val driveClient = createDriveClient(appConfig, tokenPath, downloadDir)
      val syncEngine = createSyncEngine(driveClient, appConfig, downloadDir)
      val progressReporter =
        ProgressReporter(
          verbose = parseResult.verbose,
          quiet = parseResult.quiet,
        )

      // Create CliApplication instance with injected services
      val app = CliApplication(driveClient, syncEngine, progressReporter)

      // Execute the command
      return try {
        app.executeCommand(parseResult, input, output, error)
      } catch (e: Exception) {
        logger.error(e) { "Command execution failed" }
        error.println("Error: ${e.message}")
        ExitCodes.FAILURE
      }
    }

    private fun createDriveClient(
      appConfig: AppConfig,
      tokenPath: Path,
      downloadDir: Path,
    ): GoogleDriveClient {
      val tokenManager = TokenManager(tokenPath)
      val serviceFactory = DriveServiceFactory(appConfig.clientId, appConfig.clientSecret)
      val driveConfig =
        DriveClientConfig(
          retryAttempts = appConfig.retryAttempts,
          retryDelaySeconds = appConfig.retryDelaySeconds,
        )

      return GoogleDriveClientImpl(
        config = driveConfig,
        serviceFactory = serviceFactory,
        tokenManager = tokenManager,
        baseDirectory = downloadDir,
      )
    }

    private fun createSyncEngine(
      driveClient: GoogleDriveClient,
      appConfig: AppConfig,
      downloadDir: Path,
    ): SyncEngine {
      val syncConfig =
        SyncEngineConfig(
          downloadDirectory = downloadDir,
          exportFormats = appConfig.exportFormats,
          maxConcurrentDownloads = appConfig.maxConcurrentDownloads,
        )

      return SyncEngineImpl(driveClient, syncConfig)
    }
  }

  private suspend fun executeCommand(
    parseResult: ParseResult,
    input: BufferedReader,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    return when (val command = parseResult.command) {
      is Command.Auth -> executeAuth(command, output, error)
      is Command.Sync -> executeSync(command, output, error)
      is Command.Status -> executeStatus(output, error)
      is Command.Reset -> executeReset(input, output, error)
    }
  }

  private suspend fun executeAuth(
    command: Command.Auth,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    return when (val result = driveClient.authenticate(command.force)) {
      else ->
        if (result.isSuccess) {
          output.println("Authentication successful")
          ExitCodes.SUCCESS
        } else {
          error.println("Authentication failed: ${result.exceptionOrNull()?.message}")
          ExitCodes.FAILURE
        }
    }
  }

  private suspend fun executeSync(
    command: Command.Sync,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    if (!driveClient.isAuthenticated()) {
      error.println("Not authenticated. Run 'auth' first.")
      return ExitCodes.FAILURE
    }

    // Handle dry-run mode
    if (command.dryRun) {
      return executeDryRun(output, error)
    }

    // Choose sync mode
    val flow =
      when {
        command.full -> syncEngine.initialSync()
        else -> syncEngine.resumeSync()
      }

    var exitCode = ExitCodes.SUCCESS
    flow.collect { event ->
      progressReporter.report(event)
      if (event is SyncEvent.Failed) {
        exitCode = ExitCodes.FAILURE
      }
    }

    return exitCode
  }

  @Suppress("UNUSED_PARAMETER")
  private suspend fun executeDryRun(
    output: PrintStream,
    error: PrintStream,
  ): Int {
    progressReporter.println("Dry run - showing what would be downloaded:")
    progressReporter.println()

    // Get sync status to show pending files
    val statusResult = syncEngine.getSyncStatus()
    if (statusResult.isFailure) {
      error.println("Failed to get sync status: ${statusResult.exceptionOrNull()?.message}")
      return ExitCodes.FAILURE
    }

    val status = statusResult.getOrThrow()
    progressReporter.println("Files to sync: ${status.pendingFiles}")
    progressReporter.println("Total size: ${formatBytes(status.totalSize)}")

    // Show failed files that would be retried
    if (status.failedFiles > 0) {
      progressReporter.println()
      progressReporter.println("Previously failed files (${status.failedFiles} total):")

      val failedFilesResult = syncEngine.getFailedFiles()
      if (failedFilesResult.isSuccess) {
        val failedFiles = failedFilesResult.getOrThrow()
        for (file in failedFiles.take(10)) {
          progressReporter.println(
            "  - ${file.name}: ${file.errorMessage ?: "Unknown error"}",
          )
        }
        if (failedFiles.size > 10) {
          progressReporter.println("  ... and ${failedFiles.size - 10} more")
        }
      }
    }

    progressReporter.println()
    progressReporter.println("No files will be downloaded (dry run mode).")

    return ExitCodes.SUCCESS
  }

  @Suppress("UNUSED_PARAMETER")
  private suspend fun executeStatus(
    output: PrintStream,
    error: PrintStream,
  ): Int {
    return when (val result = syncEngine.getSyncStatus()) {
      else ->
        if (result.isSuccess) {
          progressReporter.displayStatus(result.getOrThrow())
          ExitCodes.SUCCESS
        } else {
          error.println("Failed to get status: ${result.exceptionOrNull()?.message}")
          ExitCodes.FAILURE
        }
    }
  }

  private fun executeReset(
    input: BufferedReader,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    output.print("This will delete all sync state. Are you sure? [y/N]: ")
    output.flush()

    val response = input.readLine()?.trim()?.lowercase()
    if (response != "y" && response != "yes") {
      output.println("Aborted.")
      return ExitCodes.SUCCESS
    }

    val stateDir =
      Path.of(System.getProperty("user.home"))
        .resolve(".google-drive-downloader")

    return try {
      Files.deleteIfExists(stateDir.resolve("state.db"))
      Files.deleteIfExists(stateDir.resolve("state.db-journal"))
      Files.deleteIfExists(stateDir.resolve("state.db-wal"))
      Files.deleteIfExists(stateDir.resolve("state.db-shm"))
      output.println("State cleared successfully.")
      ExitCodes.SUCCESS
    } catch (e: Exception) {
      error.println("Failed to clear state: ${e.message}")
      ExitCodes.FAILURE
    }
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
