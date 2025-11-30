package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import java.util.concurrent.Callable
import picocli.CommandLine.Command as PicocliCommand

/**
 * Show current sync status and statistics.
 */
@PicocliCommand(
  name = "status",
  description = ["Show current sync status and statistics"],
)
class StatusCommand : Callable<Command> {
  override fun call(): Command = Command.Status
}
