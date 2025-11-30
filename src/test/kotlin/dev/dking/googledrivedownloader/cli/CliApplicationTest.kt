package dev.dking.googledrivedownloader.cli

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliApplicationTest {
  @TempDir
  lateinit var tempDir: Path

  private lateinit var configPath: Path
  private lateinit var outputStream: ByteArrayOutputStream
  private lateinit var errorStream: ByteArrayOutputStream
  private lateinit var output: PrintStream
  private lateinit var error: PrintStream

  @BeforeEach
  fun setUp() {
    configPath = tempDir.resolve("config.json")
    outputStream = ByteArrayOutputStream()
    errorStream = ByteArrayOutputStream()
    output = PrintStream(outputStream)
    error = PrintStream(errorStream)
  }

  @AfterEach
  fun tearDown() {
    output.close()
    error.close()
  }

  private fun createValidConfig() {
    val configJson =
      """
      {
        "download_directory": "${tempDir.resolve("downloads").toString().replace("\\", "\\\\")}",
        "client_id": "test-client-id",
        "client_secret": "test-client-secret"
      }
      """.trimIndent()
    Files.writeString(configPath, configJson)
  }

  private fun createInput(text: String): BufferedReader {
    return BufferedReader(StringReader(text))
  }

  // Tests for configuration errors

  @Test
  fun `returns USER_ERROR when config file does not exist`() =
    runTest {
      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "auth"),
          input = createInput(""),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.USER_ERROR, exitCode)
      val errorOutput = errorStream.toString()
      assertTrue(errorOutput.contains("not found") || errorOutput.contains("Error"))
    }

  @Test
  fun `returns USER_ERROR when config file has invalid JSON`() =
    runTest {
      Files.writeString(configPath, "{ invalid json }")

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "auth"),
          input = createInput(""),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.USER_ERROR, exitCode)
    }

  @Test
  fun `returns USER_ERROR when config file is missing required fields`() =
    runTest {
      Files.writeString(configPath, """{"download_directory": "/tmp"}""")

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "auth"),
          input = createInput(""),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.USER_ERROR, exitCode)
    }

  // Tests for reset command

  @Test
  fun `reset command aborts when user enters N`() =
    runTest {
      createValidConfig()

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "reset"),
          input = createInput("N\n"),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.SUCCESS, exitCode)
      assertTrue(outputStream.toString().contains("Aborted"))
    }

  @Test
  fun `reset command aborts when user enters empty line`() =
    runTest {
      createValidConfig()

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "reset"),
          input = createInput("\n"),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.SUCCESS, exitCode)
      assertTrue(outputStream.toString().contains("Aborted"))
    }

  @Test
  fun `reset command clears state when user enters y`() =
    runTest {
      createValidConfig()

      // Create a mock state file
      val stateDir = tempDir.resolve(".google-drive-downloader")
      Files.createDirectories(stateDir)
      Files.writeString(stateDir.resolve("state.db"), "mock database content")

      // We need to override the state directory, but since CliApplication uses
      // System.getProperty("user.home"), this test is limited.
      // For now, we just verify the confirmation flow works.

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "reset"),
          input = createInput("y\n"),
          output = output,
          error = error,
        )

      // The reset command will try to delete from the real home directory,
      // but it should succeed even if files don't exist
      assertEquals(CliApplication.ExitCodes.SUCCESS, exitCode)
      assertTrue(outputStream.toString().contains("State cleared successfully"))
    }

  @Test
  fun `reset command accepts yes as confirmation`() =
    runTest {
      createValidConfig()

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "reset"),
          input = createInput("yes\n"),
          output = output,
          error = error,
        )

      assertEquals(CliApplication.ExitCodes.SUCCESS, exitCode)
      assertTrue(outputStream.toString().contains("State cleared successfully"))
    }

  // Tests for command-line parsing errors

  @Test
  fun `returns non-zero for invalid command`() =
    runTest {
      createValidConfig()

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "invalid-command"),
          input = createInput(""),
          output = output,
          error = error,
        )

      assertTrue(exitCode != CliApplication.ExitCodes.SUCCESS)
    }

  @Test
  fun `returns non-zero for invalid option`() =
    runTest {
      createValidConfig()

      val exitCode =
        CliApplication.main(
          args = arrayOf("--config", configPath.toString(), "sync", "--invalid-option"),
          input = createInput(""),
          output = output,
          error = error,
        )

      assertTrue(exitCode != CliApplication.ExitCodes.SUCCESS)
    }
}
