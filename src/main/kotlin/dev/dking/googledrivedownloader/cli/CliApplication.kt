package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.cli.commands.CommandParser
import dev.dking.googledrivedownloader.cli.commands.ParseResult
import dev.dking.googledrivedownloader.sync.SyncEvent
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
object CliApplication {
  /**
   * Exit codes for the CLI application.
   */
  object ExitCodes {
    const val SUCCESS = 0
    const val FAILURE = 1
    const val USER_ERROR = 2
  }

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

    // Create component factory
    val factory = ComponentFactory(appConfig)
    val reporter =
      factory.createProgressReporter(
        verbose = parseResult.verbose,
        quiet = parseResult.quiet,
      )

    // Execute the command
    return try {
      executeCommand(parseResult, factory, reporter, input, output, error)
    } catch (e: Exception) {
      logger.error(e) { "Command execution failed" }
      error.println("Error: ${e.message}")
      ExitCodes.FAILURE
    }
  }

  private suspend fun executeCommand(
    parseResult: ParseResult,
    factory: ComponentFactory,
    reporter: ProgressReporter,
    input: BufferedReader,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    return when (val command = parseResult.command) {
      is Command.Auth -> executeAuth(command, factory, output, error)
      is Command.Sync -> executeSync(command, factory, reporter, output, error)
      is Command.Status -> executeStatus(factory, reporter, output, error)
      is Command.Reset -> executeReset(input, output, error)
    }
  }

  private suspend fun executeAuth(
    command: Command.Auth,
    factory: ComponentFactory,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    val client = factory.createDriveClient()

    return when (val result = client.authenticate(command.force)) {
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
    factory: ComponentFactory,
    reporter: ProgressReporter,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    val client = factory.createDriveClient()

    if (!client.isAuthenticated()) {
      error.println("Not authenticated. Run 'auth' first.")
      return ExitCodes.FAILURE
    }

    val engine = factory.createSyncEngine(client)

    // Handle dry-run mode
    if (command.dryRun) {
      return executeDryRun(engine, reporter, output, error)
    }

    // Choose sync mode
    val flow =
      when {
        command.full -> engine.initialSync()
        else -> engine.resumeSync()
      }

    var exitCode = ExitCodes.SUCCESS
    flow.collect { event ->
      reporter.report(event)
      if (event is SyncEvent.Failed) {
        exitCode = ExitCodes.FAILURE
      }
    }

    return exitCode
  }

  @Suppress("UNUSED_PARAMETER")
  private suspend fun executeDryRun(
    engine: dev.dking.googledrivedownloader.sync.SyncEngine,
    reporter: ProgressReporter,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    output.println("Dry run - showing what would be downloaded:")
    output.println()

    // Get sync status to show pending files
    return when (val result = engine.getSyncStatus()) {
      else ->
        if (result.isSuccess) {
          val status = result.getOrThrow()
          output.println("Files to sync: ${status.pendingFiles}")
          output.println("Total size: ${formatBytes(status.totalSize)}")

          if (status.failedFiles > 0) {
            output.println("Previously failed files: ${status.failedFiles}")
          }

          ExitCodes.SUCCESS
        } else {
          error.println("Failed to get sync status: ${result.exceptionOrNull()?.message}")
          ExitCodes.FAILURE
        }
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private suspend fun executeStatus(
    factory: ComponentFactory,
    reporter: ProgressReporter,
    output: PrintStream,
    error: PrintStream,
  ): Int {
    val client = factory.createDriveClient()
    val engine = factory.createSyncEngine(client)

    return when (val result = engine.getSyncStatus()) {
      else ->
        if (result.isSuccess) {
          reporter.displayStatus(result.getOrThrow())
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
