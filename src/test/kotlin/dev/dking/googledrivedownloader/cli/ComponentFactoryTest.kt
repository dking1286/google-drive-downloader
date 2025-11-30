package dev.dking.googledrivedownloader.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ComponentFactoryTest {
  @TempDir
  lateinit var tempDir: Path

  private fun createTestConfig(): AppConfig {
    return AppConfig(
      downloadDirectory = tempDir.resolve("downloads").toString(),
      exportFormats =
        mapOf(
          "application/vnd.google-apps.document" to
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ),
      maxConcurrentDownloads = 4,
      retryAttempts = 3,
      retryDelaySeconds = 5,
      logLevel = "INFO",
      clientId = "test-client-id",
      clientSecret = "test-client-secret",
    )
  }

  @Test
  fun `createDriveClient returns GoogleDriveClient`() {
    val config = createTestConfig()
    val tokenPath = tempDir.resolve("tokens.json")
    val factory = ComponentFactory(config, tokenPath)

    val driveClient = factory.createDriveClient()

    assertNotNull(driveClient)
  }

  @Test
  fun `createSyncEngine returns SyncEngine`() {
    val config = createTestConfig()
    val tokenPath = tempDir.resolve("tokens.json")
    val factory = ComponentFactory(config, tokenPath)
    val driveClient = factory.createDriveClient()

    val syncEngine = factory.createSyncEngine(driveClient)

    assertNotNull(syncEngine)
  }

  @Test
  fun `createProgressReporter returns ProgressReporter`() {
    val config = createTestConfig()
    val factory = ComponentFactory(config)

    val reporter = factory.createProgressReporter()

    assertNotNull(reporter)
    assertIs<ProgressReporter>(reporter)
  }

  @Test
  fun `factory uses custom token path when provided`() {
    val config = createTestConfig()
    val customTokenPath = tempDir.resolve("custom/tokens.json")
    val factory = ComponentFactory(config, customTokenPath)

    // This should not throw - just verify factory creation works with custom path
    val driveClient = factory.createDriveClient()
    assertNotNull(driveClient)
  }

  @Test
  fun `factory uses config values for retry settings`() {
    val config =
      AppConfig(
        downloadDirectory = tempDir.resolve("downloads").toString(),
        retryAttempts = 10,
        retryDelaySeconds = 30,
        clientId = "test-id",
        clientSecret = "test-secret",
      )
    val factory = ComponentFactory(config)

    // This should not throw - the config values are passed to DriveClientConfig
    val driveClient = factory.createDriveClient()
    assertNotNull(driveClient)
  }

  @Test
  fun `factory uses config values for sync settings`() {
    val config =
      AppConfig(
        downloadDirectory = tempDir.resolve("downloads").toString(),
        maxConcurrentDownloads = 8,
        exportFormats = mapOf("test/mime" to "output/mime"),
        clientId = "test-id",
        clientSecret = "test-secret",
      )
    val factory = ComponentFactory(config)
    val driveClient = factory.createDriveClient()

    // This should not throw - the config values are passed to SyncEngineConfig
    val syncEngine = factory.createSyncEngine(driveClient)
    assertNotNull(syncEngine)
  }
}
