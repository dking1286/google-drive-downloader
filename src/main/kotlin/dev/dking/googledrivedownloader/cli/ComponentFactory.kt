package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.api.impl.DriveServiceFactory
import dev.dking.googledrivedownloader.api.impl.GoogleDriveClientImpl
import dev.dking.googledrivedownloader.api.impl.TokenManager
import dev.dking.googledrivedownloader.sync.SyncEngine
import dev.dking.googledrivedownloader.sync.SyncEngineConfig
import dev.dking.googledrivedownloader.sync.impl.SyncEngineImpl
import java.nio.file.Path

/**
 * Factory for creating properly configured application components.
 * Handles dependency injection by wiring together the various services.
 */
class ComponentFactory(
  private val appConfig: AppConfig,
  private val tokenPath: Path =
    Path.of(System.getProperty("user.home"))
      .resolve(".google-drive-downloader/tokens.json"),
) {
  private val downloadDir: Path = Path.of(appConfig.downloadDirectory)

  /**
   * Create a configured Google Drive client.
   */
  fun createDriveClient(): GoogleDriveClient {
    val tokenManager = TokenManager(tokenPath)
    val serviceFactory = DriveServiceFactory(appConfig.clientId, appConfig.clientSecret)
    val driveConfig =
      DriveClientConfig(
        retryAttempts = appConfig.retryAttempts,
        retryDelaySeconds = appConfig.retryDelaySeconds,
      )

    return GoogleDriveClientImpl(
      config = driveConfig,
      serviceFactory = serviceFactory,
      tokenManager = tokenManager,
      baseDirectory = downloadDir,
    )
  }

  /**
   * Create a configured sync engine.
   *
   * @param driveClient The Google Drive client to use for syncing
   */
  fun createSyncEngine(driveClient: GoogleDriveClient): SyncEngine {
    val syncConfig =
      SyncEngineConfig(
        downloadDirectory = downloadDir,
        exportFormats = appConfig.exportFormats,
        maxConcurrentDownloads = appConfig.maxConcurrentDownloads,
      )

    return SyncEngineImpl(driveClient, syncConfig)
  }

  /**
   * Create a progress reporter.
   */
  fun createProgressReporter(): ProgressReporter {
    return ProgressReporter()
  }
}
