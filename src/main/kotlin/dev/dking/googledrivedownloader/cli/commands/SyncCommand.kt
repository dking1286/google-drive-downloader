package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable
import picocli.CommandLine.Command as PicocliCommand

/**
 * Synchronize files from Google Drive.
 */
@PicocliCommand(
  name = "sync",
  description = ["Synchronize files from Google Drive"],
)
class SyncCommand : Callable<Command> {
  @Option(
    names = ["--full"],
    description = ["Force full sync instead of incremental"],
  )
  var full: Boolean = false

  @Option(
    names = ["--dry-run", "-n"],
    description = ["Show what would be downloaded without actually downloading"],
  )
  var dryRun: Boolean = false

  @Option(
    names = ["--include"],
    description = ["Only sync files matching pattern (glob syntax)"],
  )
  var includePattern: String? = null

  @Option(
    names = ["--exclude"],
    description = ["Skip files matching pattern (glob syntax)"],
  )
  var excludePattern: String? = null

  @Option(
    names = ["--max-size"],
    description = ["Skip files larger than N bytes"],
  )
  var maxSize: Long? = null

  override fun call(): Command =
    Command.Sync(
      full = full,
      dryRun = dryRun,
      includePattern = includePattern,
      excludePattern = excludePattern,
      maxSize = maxSize,
    )
}
