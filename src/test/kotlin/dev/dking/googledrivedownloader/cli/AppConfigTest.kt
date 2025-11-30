package dev.dking.googledrivedownloader.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppConfigTest {
  @TempDir
  lateinit var tempDir: Path

  private lateinit var configPath: Path

  @BeforeEach
  fun setUp() {
    configPath = tempDir.resolve("config.json")
  }

  @AfterEach
  fun tearDown() {
    Files.deleteIfExists(configPath)
  }

  @Test
  fun `load returns success for valid config with all fields`() {
    val configJson =
      """
      {
        "download_directory": "/home/user/downloads",
        "export_formats": {
          "application/vnd.google-apps.document": "application/pdf"
        },
        "max_concurrent_downloads": 8,
        "retry_attempts": 5,
        "retry_delay_seconds": 10,
        "log_level": "DEBUG",
        "client_id": "test-client-id",
        "client_secret": "test-client-secret"
      }
      """.trimIndent()

    Files.writeString(configPath, configJson)

    val result = AppConfig.load(configPath)

    assertTrue(result.isSuccess)
    val config = result.getOrThrow()
    assertEquals("/home/user/downloads", config.downloadDirectory)
    assertEquals(
      mapOf("application/vnd.google-apps.document" to "application/pdf"),
      config.exportFormats,
    )
    assertEquals(8, config.maxConcurrentDownloads)
    assertEquals(5, config.retryAttempts)
    assertEquals(10, config.retryDelaySeconds)
    assertEquals("DEBUG", config.logLevel)
    assertEquals("test-client-id", config.clientId)
    assertEquals("test-client-secret", config.clientSecret)
  }

  @Test
  fun `load returns success with default values for optional fields`() {
    val configJson =
      """
      {
        "download_directory": "/home/user/downloads",
        "client_id": "test-client-id",
        "client_secret": "test-client-secret"
      }
      """.trimIndent()

    Files.writeString(configPath, configJson)

    val result = AppConfig.load(configPath)

    assertTrue(result.isSuccess)
    val config = result.getOrThrow()
    assertEquals("/home/user/downloads", config.downloadDirectory)
    assertEquals(4, config.maxConcurrentDownloads)
    assertEquals(3, config.retryAttempts)
    assertEquals(5, config.retryDelaySeconds)
    assertEquals("INFO", config.logLevel)
    // Should have default export formats
    assertTrue(config.exportFormats.containsKey("application/vnd.google-apps.document"))
    assertTrue(config.exportFormats.containsKey("application/vnd.google-apps.spreadsheet"))
  }

  @Test
  fun `load returns failure when file does not exist`() {
    val nonExistentPath = tempDir.resolve("nonexistent.json")

    val result = AppConfig.load(nonExistentPath)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<ConfigurationException>(exception)
    assertTrue(exception.message!!.contains("not found"))
  }

  @Test
  fun `load returns failure for invalid JSON`() {
    val invalidJson = "{ invalid json content"
    Files.writeString(configPath, invalidJson)

    val result = AppConfig.load(configPath)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<ConfigurationException>(exception)
  }

  @Test
  fun `load returns failure when required fields are missing`() {
    val configJson =
      """
      {
        "download_directory": "/home/user/downloads"
      }
      """.trimIndent()

    Files.writeString(configPath, configJson)

    val result = AppConfig.load(configPath)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<ConfigurationException>(exception)
  }

  @Test
  fun `load ignores unknown fields in config`() {
    val configJson =
      """
      {
        "download_directory": "/home/user/downloads",
        "client_id": "test-client-id",
        "client_secret": "test-client-secret",
        "unknown_field": "should be ignored"
      }
      """.trimIndent()

    Files.writeString(configPath, configJson)

    val result = AppConfig.load(configPath)

    assertTrue(result.isSuccess)
    val config = result.getOrThrow()
    assertEquals("/home/user/downloads", config.downloadDirectory)
  }

  @Test
  fun `defaultConfigPath returns path under user home directory`() {
    val path = AppConfig.defaultConfigPath()

    val userHome = System.getProperty("user.home")
    assertTrue(path.toString().startsWith(userHome))
    assertTrue(path.toString().contains(".google-drive-downloader"))
    assertTrue(path.toString().endsWith("config.json"))
  }
}
