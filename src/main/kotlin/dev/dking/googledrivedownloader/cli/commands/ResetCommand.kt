package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import java.util.concurrent.Callable
import picocli.CommandLine.Command as PicocliCommand

/**
 * Clear local state and start fresh.
 */
@PicocliCommand(
  name = "reset",
  description = ["Clear local state and start fresh"],
)
class ResetCommand : Callable<Command> {
  override fun call(): Command = Command.Reset
}
