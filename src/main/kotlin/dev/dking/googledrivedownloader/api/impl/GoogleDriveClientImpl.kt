package dev.dking.googledrivedownloader.api.impl

import com.google.api.services.drive.model.Change
import com.google.api.services.drive.model.File
import dev.dking.googledrivedownloader.api.ChangeList
import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.DriveFile
import dev.dking.googledrivedownloader.api.FileChange
import dev.dking.googledrivedownloader.api.FileField
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.files.PathValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Implementation of GoogleDriveClient using Google Drive API v3.
 * Handles OAuth 2.0 authentication, file listing, change detection, and file downloads
 * with retry logic and exponential backoff.
 *
 * @param config Configuration for retry attempts and delays
 * @param serviceFactory Factory for creating Google Drive service instances
 * @param tokenManager Manager for storing and retrieving OAuth tokens
 * @param baseDirectory Base directory for all file operations. All output paths must be within this directory.
 */
class GoogleDriveClientImpl(
  private val config: DriveClientConfig = DriveClientConfig(),
  private val serviceFactory: DriveServiceFactory,
  private val tokenManager: TokenManager,
  private val baseDirectory: Path,
) : GoogleDriveClient {
  private val retryHandler = RetryHandler(config.retryAttempts, config.retryDelaySeconds)

  /**
   * Validates that the given output path is safe for file operations.
   * Uses PathValidator for both path traversal and symlink attack prevention.
   *
   * @param outputPath The path to validate
   * @throws IllegalArgumentException if validation fails
   */
  private fun validateOutputPath(outputPath: Path) {
    PathValidator.validatePath(outputPath, baseDirectory).onFailure { exception ->
      throw IllegalArgumentException(exception.message, exception)
    }
  }

  override suspend fun authenticate(forceReauth: Boolean): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        logger.info { "Starting authentication flow (forceReauth=$forceReauth)" }

        // Check for cached valid tokens if not forcing reauth
        if (!forceReauth) {
          val tokens = tokenManager.loadTokens()
          if (tokens != null && tokenManager.isTokenValid(tokens)) {
            logger.debug { "Using cached tokens (valid until ${tokens.expiresAt})" }
            return@withContext Result.success(Unit)
          }
        }

        // Perform OAuth flow
        val credential = serviceFactory.authorize()

        // Save tokens
        tokenManager.saveTokens(credential)

        logger.info { "Authentication successful" }
        Result.success(Unit)
      } catch (e: AuthenticationException) {
        logger.error(e) { "Authentication failed" }
        Result.failure(e)
      } catch (e: Exception) {
        logger.error(e) { "Unexpected error during authentication" }
        Result.failure(AuthenticationException("Authentication failed: ${e.message}", e))
      }
    }

  override fun isAuthenticated(): Boolean {
    val tokens =
      tokenManager.loadTokens() ?: run {
        logger.debug { "Authentication check: false (no tokens)" }
        return false
      }

    val isValid = tokenManager.isTokenValid(tokens)
    logger.debug { "Authentication check: $isValid" }
    return isValid
  }

  override suspend fun getStartPageToken(): Result<String> =
    withContext(Dispatchers.IO) {
      logger.info { "Fetching start page token" }

      retryHandler.executeWithRetry {
        val service = createDriveService()
        val response = service.changes().getStartPageToken().execute()
        val token =
          response.startPageToken
            ?: throw ApiException("No start page token in response")

        logger.debug { "Received start page token: $token" }
        token
      }
    }

  override suspend fun listAllFiles(fields: Set<FileField>): Result<List<DriveFile>> =
    withContext(Dispatchers.IO) {
      logger.info { "Listing all files" }

      retryHandler.executeWithRetry {
        val service = createDriveService()

        // Define required fields for non-nullable DriveFile properties
        val requiredFields =
          setOf(
            FileField.ID,
            FileField.NAME,
            FileField.MIME_TYPE,
            FileField.MODIFIED_TIME,
          )

        // Union required fields with caller-requested fields to prevent NPEs
        val fieldsToRequest = requiredFields + fields

        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var pageCount = 0

        do {
          pageCount++
          val request =
            service.files().list()
              .setPageSize(1000)
              .setFields(
                "nextPageToken, files(${mapFieldsToString(fieldsToRequest)})",
              )
              .setQ("trashed = false")

          if (pageToken != null) {
            request.pageToken = pageToken
          }

          val result = request.execute()

          result.files?.forEach { file ->
            allFiles.add(mapToDriveFile(file))
          }

          logger.debug { "Fetched ${result.files?.size ?: 0} files (page $pageCount)" }
          pageToken = result.nextPageToken
        } while (pageToken != null)

        logger.info { "Listed ${allFiles.size} files total" }
        allFiles
      }
    }

  override suspend fun listChanges(pageToken: String): Result<ChangeList> =
    withContext(Dispatchers.IO) {
      logger.info { "Listing changes since token $pageToken" }

      retryHandler.executeWithRetry {
        val service = createDriveService()
        val allChanges = mutableListOf<FileChange>()
        var currentPageToken: String? = pageToken
        var newStartPageToken: String? = null
        var pageCount = 0

        do {
          pageCount++
          val allFields = mapAllFields()
          val fieldsParam =
            "nextPageToken, newStartPageToken, changes(fileId, removed, file($allFields))"
          val request =
            service.changes().list(currentPageToken)
              .setPageSize(1000)
              .setFields(fieldsParam)

          val result = request.execute()

          result.changes?.forEach { change ->
            allChanges.add(mapToFileChange(change))
          }

          logger.debug { "Fetched ${result.changes?.size ?: 0} changes (page $pageCount)" }
          currentPageToken = result.nextPageToken
          newStartPageToken = result.newStartPageToken ?: newStartPageToken
        } while (currentPageToken != null)

        val finalToken =
          newStartPageToken
            ?: throw ApiException("No newStartPageToken in response")

        logger.info { "Listed ${allChanges.size} changes total, new token: $finalToken" }

        ChangeList(
          changes = allChanges,
          newStartPageToken = finalToken,
        )
      }
    }

  override suspend fun downloadFile(
    fileId: String,
    outputPath: Path,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      validateOutputPath(outputPath)
      logger.info { "Downloading file $fileId to $outputPath" }
      downloadFileInternal(fileId, outputPath, onProgress, isRetry = false)
    }

  /**
   * Internal download implementation with MD5 verification and retry-once on checksum mismatch.
   *
   * @param fileId Google Drive file ID
   * @param outputPath Local path where file should be saved
   * @param onProgress Progress callback
   * @param isRetry Whether this is a retry attempt after MD5 mismatch
   * @return Result indicating success or failure
   */
  private suspend fun downloadFileInternal(
    fileId: String,
    outputPath: Path,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    isRetry: Boolean,
  ): Result<Unit> =
    retryHandler.executeWithRetry {
      val service = createDriveService()

      // Get file metadata for size and MD5 checksum
      val metadata = service.files().get(fileId).setFields("size,md5Checksum").execute()
      val totalBytes = metadata.size?.toLong()
      val expectedMd5 = metadata.md5Checksum

      // Create parent directories
      Files.createDirectories(outputPath.parent)

      // Create temp file path with unpredictable name to prevent symlink attacks
      val tempPath = outputPath.parent.resolve(".${UUID.randomUUID()}.download.tmp")

      try {
        // Download to temp file using CountingOutputStream for accurate progress
        val countingStream = CountingOutputStream(Files.newOutputStream(tempPath))
        countingStream.use { outputStream ->
          val request = service.files().get(fileId)

          // Configure progress listener
          request.mediaHttpDownloader?.apply {
            isDirectDownloadEnabled = false
            setProgressListener {
              onProgress(countingStream.bytesWritten, totalBytes)
            }
          }

          // Execute download
          request.executeMediaAndDownloadTo(outputStream)
        }

        // Verify MD5 checksum if available
        if (expectedMd5 != null) {
          val actualMd5 = Md5Utils.computeMd5(tempPath)
          if (actualMd5 != expectedMd5) {
            Files.deleteIfExists(tempPath)
            if (!isRetry) {
              logger.warn {
                "MD5 mismatch for file $fileId: expected $expectedMd5, got $actualMd5. Retrying..."
              }
              // Retry once - this will throw if it fails again
              return@executeWithRetry downloadFileInternal(
                fileId,
                outputPath,
                onProgress,
                isRetry = true,
              ).getOrThrow()
            }
            throw ApiException(
              "MD5 checksum mismatch after retry: expected $expectedMd5, got $actualMd5",
            )
          }
          logger.debug { "MD5 checksum verified: $actualMd5" }
        } else {
          logger.debug { "No MD5 checksum available for verification (file $fileId)" }
        }

        // Atomic rename
        Files.move(
          tempPath,
          outputPath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )

        logger.info { "Downloaded file successfully: $outputPath" }
      } catch (e: Exception) {
        // Clean up temp file on error
        Files.deleteIfExists(tempPath)
        logger.error(e) { "Download failed, cleaning up temp file" }
        throw e
      }
    }

  override suspend fun exportFile(
    fileId: String,
    exportMimeType: String,
    outputPath: Path,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      validateOutputPath(outputPath)
      logger.info { "Exporting file $fileId to $outputPath as $exportMimeType" }

      retryHandler.executeWithRetry {
        val service = createDriveService()

        // Create parent directories
        Files.createDirectories(outputPath.parent)

        // Create temp file path with unpredictable name to prevent symlink attacks
        val tempPath = outputPath.parent.resolve(".${UUID.randomUUID()}.download.tmp")

        try {
          // Export file
          Files.newOutputStream(tempPath).use { outputStream ->
            service.files().export(fileId, exportMimeType)
              .executeMediaAndDownloadTo(outputStream)
          }

          // Atomic rename
          Files.move(
            tempPath,
            outputPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )

          logger.info { "Exported file successfully: $outputPath" }
        } catch (e: Exception) {
          // Clean up temp file on error
          Files.deleteIfExists(tempPath)
          logger.error(e) { "Export failed, cleaning up temp file" }
          throw e
        }
      }
    }

  /**
   * Create a Drive service instance using stored tokens.
   * @throws AuthenticationException if not authenticated
   */
  private fun createDriveService(): com.google.api.services.drive.Drive {
    val tokens =
      tokenManager.loadTokens()
        ?: throw AuthenticationException("Not authenticated. Please call authenticate() first.")

    if (!tokenManager.isTokenValid(tokens)) {
      throw AuthenticationException("Tokens expired. Please call authenticate() again.")
    }

    return serviceFactory.createDriveServiceFromTokens(
      tokens.accessToken,
      tokens.refreshToken,
    )
  }

  /**
   * Map FileField enum to Google Drive API field strings.
   */
  private fun mapFieldsToString(fields: Set<FileField>): String {
    return fields.joinToString(",") { field ->
      when (field) {
        FileField.ID -> "id"
        FileField.NAME -> "name"
        FileField.MIME_TYPE -> "mimeType"
        FileField.PARENTS -> "parents"
        FileField.MD5_CHECKSUM -> "md5Checksum"
        FileField.MODIFIED_TIME -> "modifiedTime"
        FileField.SIZE -> "size"
        FileField.SHORTCUT_DETAILS -> "shortcutDetails"
        FileField.TRASHED -> "trashed"
      }
    }
  }

  /**
   * Get all fields for complete file information.
   */
  private fun mapAllFields(): String {
    return mapFieldsToString(FileField.values().toSet())
  }

  /**
   * Map Google Drive API File to our DriveFile data class.
   */
  private fun mapToDriveFile(apiFile: File): DriveFile {
    return DriveFile(
      id = apiFile.id,
      name = apiFile.name,
      mimeType = apiFile.mimeType,
      parentId = apiFile.parents?.firstOrNull(),
      md5Checksum = apiFile.md5Checksum,
      modifiedTime = Instant.parse(apiFile.modifiedTime.toString()),
      size = apiFile.getSize(),
      isFolder = apiFile.mimeType == "application/vnd.google-apps.folder",
      shortcutTargetId = apiFile.shortcutDetails?.targetId,
      trashed = apiFile.trashed ?: false,
    )
  }

  /**
   * Map Google Drive API Change to our FileChange data class.
   */
  private fun mapToFileChange(apiChange: Change): FileChange {
    return FileChange(
      fileId = apiChange.fileId,
      removed = apiChange.removed ?: false,
      file =
        if (apiChange.file != null && apiChange.removed != true) {
          mapToDriveFile(apiChange.file)
        } else {
          null
        },
    )
  }
}
