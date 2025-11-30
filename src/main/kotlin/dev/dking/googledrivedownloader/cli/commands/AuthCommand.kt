package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable
import picocli.CommandLine.Command as PicocliCommand

/**
 * Authenticate with Google Drive using OAuth 2.0.
 */
@PicocliCommand(
  name = "auth",
  description = ["Authenticate with Google Drive using OAuth 2.0"],
)
class AuthCommand : Callable<Command> {
  @Option(
    names = ["--force", "-f"],
    description = ["Force re-authentication even if already authenticated"],
  )
  var force: Boolean = false

  override fun call(): Command = Command.Auth(force)
}
