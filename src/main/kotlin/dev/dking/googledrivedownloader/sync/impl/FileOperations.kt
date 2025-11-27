package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.api.DriveFile
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.sync.FileRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

/**
 * Helper class for file operations including path resolution, sanitization, and downloading.
 */
internal class FileOperations(
  private val downloadDirectory: Path,
  private val driveClient: GoogleDriveClient,
  private val exportFormats: Map<String, String>,
  private val databaseManager: DatabaseManager,
) {
  companion object {
    private const val GOOGLE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    private const val GOOGLE_SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"
    private const val GOOGLE_WORKSPACE_PREFIX = "application/vnd.google-apps."

    // Export format extensions
    private val EXPORT_EXTENSIONS =
      mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
        "image/png" to ".png",
      )
  }

  /**
   * Sanitize a filename to be safe for the local filesystem.
   */
  fun sanitizeFilename(name: String): String {
    var sanitized =
      name
        .replace("/", "_")
        .replace("\u0000", "_")

    // Truncate to 255 bytes (UTF-8)
    val bytes = sanitized.toByteArray(Charsets.UTF_8)
    if (bytes.size > 255) {
      sanitized =
        String(bytes.copyOf(255), Charsets.UTF_8)
          .trimEnd { it.code == 0xFFFD } // Remove replacement characters
    }

    return sanitized
  }

  /**
   * Build the local path for a file based on its Drive hierarchy.
   */
  fun buildLocalPath(
    fileId: String,
    fileName: String,
    mimeType: String,
    parentId: String?,
  ): String {
    val pathParts = mutableListOf<String>()

    // Add the file's own name first
    var nameWithExtension = sanitizeFilename(fileName)

    // Add extension for Google Workspace files
    if (mimeType.startsWith(GOOGLE_WORKSPACE_PREFIX)) {
      val exportMimeType = exportFormats[mimeType]
      if (exportMimeType != null) {
        val extension = EXPORT_EXTENSIONS[exportMimeType]
        if (extension != null && !nameWithExtension.endsWith(extension)) {
          nameWithExtension += extension
        }
      }
    }

    pathParts.add(nameWithExtension)

    // Traverse up the tree to build the full path
    var currentParentId = parentId
    while (currentParentId != null) {
      val parentFile = databaseManager.getFile(currentParentId)
      if (parentFile == null) {
        break
      }

      pathParts.add(0, sanitizeFilename(parentFile.name))
      currentParentId = parentFile.parentId
    }

    return pathParts.joinToString("/")
  }

  /**
   * Resolve conflicts if a file with the same name already exists.
   */
  fun resolvePathConflict(path: Path): Path {
    if (!path.exists()) {
      return path
    }

    val parent = path.parent
    val fileName = path.name
    val nameParts = fileName.split(".")

    val baseName =
      if (nameParts.size > 1) {
        nameParts.dropLast(1).joinToString(".")
      } else {
        fileName
      }

    val extension =
      if (nameParts.size > 1) {
        ".${nameParts.last()}"
      } else {
        ""
      }

    var counter = 1
    var newPath: Path
    do {
      val newFileName = "$baseName ($counter)$extension"
      newPath = parent.resolve(newFileName)
      counter++
    } while (newPath.exists())

    return newPath
  }

  /**
   * Download a regular file from Google Drive.
   */
  suspend fun downloadRegularFile(
    file: FileRecord,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
  ): Result<Unit> {
    try {
      val localPath =
        downloadDirectory.resolve(
          file.localPath ?: return Result.failure(
            IllegalStateException("File ${file.id} has no local path"),
          ),
        )

      // Create parent directories
      Files.createDirectories(localPath.parent)

      // Download to temporary file
      val tempPath = localPath.resolveSibling("${localPath.name}.tmp")

      val result =
        driveClient.downloadFile(
          fileId = file.id,
          outputPath = tempPath,
          onProgress = onProgress,
        )

      if (result.isFailure) {
        // Clean up temp file on failure
        Files.deleteIfExists(tempPath)
        return result
      }

      // Atomic rename to final name
      Files.move(
        tempPath,
        localPath,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )

      logger.debug { "Downloaded file ${file.name} to $localPath" }
      return Result.success(Unit)
    } catch (e: Exception) {
      logger.error(e) { "Failed to download file ${file.name}" }
      return Result.failure(e)
    }
  }

  /**
   * Export a Google Workspace file.
   */
  suspend fun exportWorkspaceFile(
    file: FileRecord,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
  ): Result<Unit> {
    try {
      val exportMimeType =
        exportFormats[file.mimeType]
          ?: return Result.failure(
            IllegalStateException("No export format configured for ${file.mimeType}"),
          )

      val localPath =
        downloadDirectory.resolve(
          file.localPath ?: return Result.failure(
            IllegalStateException("File ${file.id} has no local path"),
          ),
        )

      // Create parent directories
      Files.createDirectories(localPath.parent)

      // Download to temporary file
      val tempPath = localPath.resolveSibling("${localPath.name}.tmp")

      val result =
        driveClient.exportFile(
          fileId = file.id,
          exportMimeType = exportMimeType,
          outputPath = tempPath,
        )

      if (result.isFailure) {
        // Clean up temp file on failure
        Files.deleteIfExists(tempPath)
        return result
      }

      // Report progress (we don't know size beforehand for exports)
      if (tempPath.exists()) {
        val size = tempPath.fileSize()
        onProgress(size, size)
      }

      // Atomic rename to final name
      Files.move(
        tempPath,
        localPath,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )

      logger.debug { "Exported file ${file.name} to $localPath" }
      return Result.success(Unit)
    } catch (e: Exception) {
      logger.error(e) { "Failed to export file ${file.name}" }
      return Result.failure(e)
    }
  }

  /**
   * Create a local directory for a folder.
   */
  fun createFolder(file: FileRecord): Result<Unit> {
    try {
      val localPath =
        downloadDirectory.resolve(
          file.localPath ?: return Result.failure(
            IllegalStateException("Folder ${file.id} has no local path"),
          ),
        )

      Files.createDirectories(localPath)
      logger.debug { "Created folder ${file.name} at $localPath" }
      return Result.success(Unit)
    } catch (e: Exception) {
      logger.error(e) { "Failed to create folder ${file.name}" }
      return Result.failure(e)
    }
  }

  /**
   * Download a file based on its type (regular, workspace, or folder).
   */
  suspend fun downloadFile(
    file: FileRecord,
    onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
  ): Result<Unit> {
    val isWorkspaceFile =
      file.mimeType.startsWith(GOOGLE_WORKSPACE_PREFIX) &&
        file.mimeType != GOOGLE_FOLDER_MIME_TYPE
    return when {
      file.isFolder -> createFolder(file)
      isWorkspaceFile -> exportWorkspaceFile(file, onProgress)
      else -> downloadRegularFile(file, onProgress)
    }
  }

  /**
   * Convert DriveFile to FileRecord with local path resolution.
   */
  fun driveFileToRecord(driveFile: DriveFile): FileRecord {
    // Build local path
    val localPath =
      buildLocalPath(driveFile.id, driveFile.name, driveFile.mimeType, driveFile.parentId)

    // Insert/update to database with the built path
    databaseManager.upsertFile(
      id = driveFile.id,
      name = driveFile.name,
      mimeType = driveFile.mimeType,
      parentId = driveFile.parentId,
      localPath = localPath,
      remoteMd5 = driveFile.md5Checksum,
      modifiedTime = driveFile.modifiedTime,
      size = driveFile.size,
      isFolder = driveFile.isFolder,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    return FileRecord(
      id = driveFile.id,
      name = driveFile.name,
      mimeType = driveFile.mimeType,
      parentId = driveFile.parentId,
      localPath = localPath,
      remoteMd5 = driveFile.md5Checksum,
      modifiedTime = driveFile.modifiedTime,
      size = driveFile.size,
      isFolder = driveFile.isFolder,
      syncStatus = FileRecord.SyncStatus.PENDING,
      lastSynced = null,
      errorMessage = null,
    )
  }
}
