package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import picocli.CommandLine
import picocli.CommandLine.ParameterException
import java.io.PrintWriter

/**
 * Parser for command-line arguments using Picocli.
 */
class CommandParser {
  /**
   * Parse command-line arguments.
   *
   * @param args Command-line arguments
   * @return Result containing ParseResult on success, or error on failure
   */
  fun parse(args: Array<String>): Result<ParseResult> {
    val mainCommand = MainCommand()
    val cmd = CommandLine(mainCommand)

    return try {
      val parseResult = cmd.parseArgs(*args)

      // Check if help or version was requested
      if (parseResult.isUsageHelpRequested || parseResult.isVersionHelpRequested) {
        return Result.failure(CommandParseException("Help or version requested"))
      }

      // Get the subcommand
      if (!parseResult.hasSubcommand()) {
        return Result.failure(CommandParseException("No subcommand specified"))
      }

      val subcommandResult = parseResult.subcommand()
      val commandObject = subcommandResult.commandSpec().userObject()

      val parsedCommand =
        when (commandObject) {
          is java.util.concurrent.Callable<*> -> {
            @Suppress("UNCHECKED_CAST")
            val callable = commandObject as java.util.concurrent.Callable<Command>
            callable.call()
          }
          else -> {
            return Result.failure(
              CommandParseException("Unknown command type: ${commandObject::class}"),
            )
          }
        }

      Result.success(
        ParseResult(
          command = parsedCommand,
          configPath = mainCommand.configPath,
          verbose = mainCommand.verbose,
          quiet = mainCommand.quiet,
        ),
      )
    } catch (e: ParameterException) {
      Result.failure(CommandParseException("Invalid arguments: ${e.message}", e))
    } catch (e: Exception) {
      Result.failure(CommandParseException("Command parsing failed: ${e.message}", e))
    }
  }

  /**
   * Parse command-line arguments and get the exit code.
   * This is useful for when you want Picocli to handle all output directly.
   *
   * @param args Command-line arguments
   * @param out PrintWriter for standard output
   * @param err PrintWriter for error output
   * @return Exit code
   */
  fun parseAndGetExitCode(
    args: Array<String>,
    out: PrintWriter = PrintWriter(System.out),
    err: PrintWriter = PrintWriter(System.err),
  ): Int {
    val mainCommand = MainCommand()
    val cmd =
      CommandLine(mainCommand)
        .setOut(out)
        .setErr(err)

    return cmd.execute(*args)
  }
}

/**
 * Exception thrown when command parsing fails.
 */
class CommandParseException(message: String, cause: Throwable? = null) :
  Exception(message, cause)
