package dev.dking.googledrivedownloader.cli.commands

import dev.dking.googledrivedownloader.cli.Command
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandParsingTest {
  private val parser = CommandParser()

  // Auth command tests

  @Test
  fun `auth command parses with default options`() {
    val result = parser.parse(arrayOf("auth"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    val command = parseResult.command
    assertIs<Command.Auth>(command)
    assertFalse(command.force)
  }

  @Test
  fun `auth command parses with force flag`() {
    val result = parser.parse(arrayOf("auth", "--force"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Auth>(command)
    assertTrue(command.force)
  }

  @Test
  fun `auth command parses with short force flag`() {
    val result = parser.parse(arrayOf("auth", "-f"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Auth>(command)
    assertTrue(command.force)
  }

  // Sync command tests

  @Test
  fun `sync command parses with default options`() {
    val result = parser.parse(arrayOf("sync"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertFalse(command.full)
    assertFalse(command.dryRun)
    assertNull(command.includePattern)
    assertNull(command.excludePattern)
    assertNull(command.maxSize)
  }

  @Test
  fun `sync command parses with full flag`() {
    val result = parser.parse(arrayOf("sync", "--full"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertTrue(command.full)
  }

  @Test
  fun `sync command parses with dry-run flag`() {
    val result = parser.parse(arrayOf("sync", "--dry-run"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertTrue(command.dryRun)
  }

  @Test
  fun `sync command parses with short dry-run flag`() {
    val result = parser.parse(arrayOf("sync", "-n"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertTrue(command.dryRun)
  }

  @Test
  fun `sync command parses with include pattern`() {
    val result = parser.parse(arrayOf("sync", "--include", "*.pdf"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertEquals("*.pdf", command.includePattern)
  }

  @Test
  fun `sync command parses with exclude pattern`() {
    val result = parser.parse(arrayOf("sync", "--exclude", "*.tmp"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertEquals("*.tmp", command.excludePattern)
  }

  @Test
  fun `sync command parses with max-size`() {
    val result = parser.parse(arrayOf("sync", "--max-size", "1048576"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertEquals(1048576L, command.maxSize)
  }

  @Test
  fun `sync command parses with all options`() {
    val result =
      parser.parse(
        arrayOf(
          "sync",
          "--full",
          "--dry-run",
          "--include",
          "*.doc",
          "--exclude",
          "*.bak",
          "--max-size",
          "5000000",
        ),
      )

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Sync>(command)
    assertTrue(command.full)
    assertTrue(command.dryRun)
    assertEquals("*.doc", command.includePattern)
    assertEquals("*.bak", command.excludePattern)
    assertEquals(5000000L, command.maxSize)
  }

  // Status command tests

  @Test
  fun `status command parses`() {
    val result = parser.parse(arrayOf("status"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Status>(command)
  }

  // Reset command tests

  @Test
  fun `reset command parses`() {
    val result = parser.parse(arrayOf("reset"))

    assertTrue(result.isSuccess)
    val command = result.getOrThrow().command
    assertIs<Command.Reset>(command)
  }

  // Global options tests

  @Test
  fun `global config option is captured`() {
    val result = parser.parse(arrayOf("--config", "/path/to/config.json", "auth"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertEquals(Path.of("/path/to/config.json"), parseResult.configPath)
  }

  @Test
  fun `global config short option is captured`() {
    val result = parser.parse(arrayOf("-c", "/path/to/config.json", "sync"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertEquals(Path.of("/path/to/config.json"), parseResult.configPath)
  }

  @Test
  fun `global verbose option is captured`() {
    val result = parser.parse(arrayOf("--verbose", "auth"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertTrue(parseResult.verbose)
  }

  @Test
  fun `global verbose short option is captured`() {
    val result = parser.parse(arrayOf("-v", "sync"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertTrue(parseResult.verbose)
  }

  @Test
  fun `global quiet option is captured`() {
    val result = parser.parse(arrayOf("--quiet", "status"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertTrue(parseResult.quiet)
  }

  @Test
  fun `global quiet short option is captured`() {
    val result = parser.parse(arrayOf("-q", "reset"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertTrue(parseResult.quiet)
  }

  @Test
  fun `global options default to false and null`() {
    val result = parser.parse(arrayOf("auth"))

    assertTrue(result.isSuccess)
    val parseResult = result.getOrThrow()
    assertNull(parseResult.configPath)
    assertFalse(parseResult.verbose)
    assertFalse(parseResult.quiet)
  }

  // Error handling tests

  @Test
  fun `no subcommand returns failure`() {
    val result = parser.parse(arrayOf())

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<CommandParseException>(exception)
  }

  @Test
  fun `invalid subcommand returns failure`() {
    val result = parser.parse(arrayOf("invalid-command"))

    assertTrue(result.isFailure)
  }

  @Test
  fun `invalid option returns failure`() {
    val result = parser.parse(arrayOf("sync", "--invalid-option"))

    assertTrue(result.isFailure)
  }
}
