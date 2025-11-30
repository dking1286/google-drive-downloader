package dev.dking.googledrivedownloader.cli.commands

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path

/**
 * Top-level command for the Google Drive Downloader CLI.
 * Provides global options that apply to all subcommands.
 */
@Command(
  name = "google-drive-downloader",
  mixinStandardHelpOptions = true,
  version = ["1.0.0"],
  description = ["Incrementally download files from Google Drive"],
  subcommands = [
    AuthCommand::class,
    SyncCommand::class,
    StatusCommand::class,
    ResetCommand::class,
  ],
)
class MainCommand : Runnable {
  @Option(
    names = ["--config", "-c"],
    description = ["Path to config file"],
  )
  var configPath: Path? = null

  @Option(
    names = ["--verbose", "-v"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @Option(
    names = ["--quiet", "-q"],
    description = ["Suppress non-error output"],
  )
  var quiet: Boolean = false

  override fun run() {
    // If no subcommand is specified, print help
    CommandLine(this).usage(System.out)
  }
}

/**
 * Result of parsing command-line arguments.
 */
data class ParseResult(
  val command: dev.dking.googledrivedownloader.cli.Command,
  val configPath: Path?,
  val verbose: Boolean,
  val quiet: Boolean,
)
