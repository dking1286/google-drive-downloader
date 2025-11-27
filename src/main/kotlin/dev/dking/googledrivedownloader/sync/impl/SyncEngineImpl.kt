package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.api.FileField
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.sync.FileRecord
import dev.dking.googledrivedownloader.sync.SyncEngine
import dev.dking.googledrivedownloader.sync.SyncEngineConfig
import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.SyncStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.io.path.div

private val logger = KotlinLogging.logger {}

/**
 * Implementation of SyncEngine that orchestrates file downloads from Google Drive.
 * Manages local state persistence via SQLite and emits progress events via Flow.
 */
class SyncEngineImpl(
  private val driveClient: GoogleDriveClient,
  private val config: SyncEngineConfig,
) : SyncEngine {
  private val databasePath =
    config.downloadDirectory.parent / ".google-drive-downloader" / "state.db"

  init {
    // Ensure database directory exists
    Files.createDirectories(databasePath.parent)
  }

  override fun initialSync(): Flow<SyncEvent> =
    channelFlow {
      val startTime = Instant.now()
      var filesProcessed = 0
      var bytesDownloaded = 0L
      var failedFiles = 0

      try {
        DatabaseManager(databasePath).use { db ->
          val fileOps =
            FileOperations(
              config.downloadDirectory,
              driveClient,
              config.exportFormats,
              db,
            )

          // Get start page token first
          logger.info { "Getting start page token..." }
          val tokenResult = driveClient.getStartPageToken()
          if (tokenResult.isFailure) {
            send(
              SyncEvent.Failed(
                "Failed to get start page token: ${tokenResult.exceptionOrNull()?.message}",
              ),
            )
            return@channelFlow
          }
          val pageToken = tokenResult.getOrThrow()

          // Create sync run
          val syncRunId = db.createSyncRun(startTime, pageToken)
          send(SyncEvent.Started(syncRunId, startTime))

          // List all files
          logger.info { "Listing all files from Google Drive..." }
          val filesResult =
            driveClient.listAllFiles(
              setOf(
                FileField.ID,
                FileField.NAME,
                FileField.MIME_TYPE,
                FileField.PARENTS,
                FileField.MD5_CHECKSUM,
                FileField.MODIFIED_TIME,
                FileField.SIZE,
                FileField.SHORTCUT_DETAILS,
              ),
            )

          if (filesResult.isFailure) {
            val error = "Failed to list files: ${filesResult.exceptionOrNull()?.message}"
            db.completeSyncRun(syncRunId, Instant.now(), "failed", error)
            send(SyncEvent.Failed(error))
            return@channelFlow
          }

          val driveFiles = filesResult.getOrThrow()
          send(SyncEvent.DiscoveringFiles(driveFiles.size))

          // Insert all files into database with pending status
          logger.info { "Inserting ${driveFiles.size} files into database..." }
          for (driveFile in driveFiles) {
            val fileRecord = fileOps.driveFileToRecord(driveFile)
            send(SyncEvent.FileQueued(fileRecord.id, fileRecord.name, fileRecord.size))
          }

          // Download files using breadth-first traversal
          logger.info { "Starting file downloads..." }
          val downloadResult =
            downloadPendingFiles(
              db = db,
              fileOps = fileOps,
              onProgress = { fileId, fileName, bytes, total ->
                send(SyncEvent.FileDownloading(fileId, fileName, bytes, total))
              },
              onCompleted = { fileId, fileName, bytes ->
                filesProcessed++
                bytesDownloaded += bytes
                send(SyncEvent.FileCompleted(fileId, fileName))
                send(
                  SyncEvent.Progress(
                    filesProcessed,
                    driveFiles.size,
                    bytesDownloaded,
                  ),
                )
              },
              onFailed = { fileId, fileName, error ->
                failedFiles++
                send(SyncEvent.FileFailed(fileId, fileName, error))
              },
            )

          // Save change token for incremental sync
          db.saveChangeToken(pageToken, Instant.now())

          // Complete sync run
          val duration = Duration.between(startTime, Instant.now())
          db.updateSyncRunProgress(syncRunId, filesProcessed, bytesDownloaded)
          db.completeSyncRun(syncRunId, Instant.now(), "completed")

          send(
            SyncEvent.Completed(filesProcessed, bytesDownloaded, failedFiles, duration),
          )
        }
      } catch (e: Exception) {
        logger.error(e) { "Initial sync failed" }
        send(SyncEvent.Failed("Initial sync failed: ${e.message}"))
      }
    }

  override fun incrementalSync(): Flow<SyncEvent> =
    channelFlow {
      val startTime = Instant.now()
      var filesProcessed = 0
      var bytesDownloaded = 0L
      var failedFiles = 0

      try {
        DatabaseManager(databasePath).use { db ->
          val fileOps =
            FileOperations(
              config.downloadDirectory,
              driveClient,
              config.exportFormats,
              db,
            )

          // Get saved change token
          val savedToken = db.getChangeToken()
          if (savedToken == null) {
            send(SyncEvent.Failed("No change token found. Run initial sync first."))
            return@channelFlow
          }

          // Create sync run
          val syncRunId = db.createSyncRun(startTime, savedToken)
          send(SyncEvent.Started(syncRunId, startTime))

          // Get changes since last sync
          logger.info { "Fetching changes since last sync..." }
          val changesResult = driveClient.listChanges(savedToken)
          if (changesResult.isFailure) {
            val error = "Failed to list changes: ${changesResult.exceptionOrNull()?.message}"
            db.completeSyncRun(syncRunId, Instant.now(), "failed", error)
            send(SyncEvent.Failed(error))
            return@channelFlow
          }

          val changeList = changesResult.getOrThrow()
          val changes = changeList.changes

          send(SyncEvent.DiscoveringFiles(changes.size))

          // Process changes
          logger.info { "Processing ${changes.size} changes..." }
          var pendingCount = 0
          for (change in changes) {
            if (change.removed || change.file == null) {
              // File was deleted
              logger.debug { "File ${change.fileId} was removed" }
              if (config.deleteRemovedFiles) {
                val existingFile = db.getFile(change.fileId)
                if (existingFile?.localPath != null) {
                  val localPath =
                    config.downloadDirectory.resolve(
                      existingFile.localPath,
                    )
                  Files.deleteIfExists(localPath)
                }
                db.deleteFile(change.fileId)
              }
            } else {
              val driveFile = change.file!!
              val existingFile = db.getFile(driveFile.id)

              if (existingFile == null) {
                // New file
                logger.debug { "New file: ${driveFile.name}" }
                val fileRecord = fileOps.driveFileToRecord(driveFile)
                send(
                  SyncEvent.FileQueued(
                    fileRecord.id,
                    fileRecord.name,
                    fileRecord.size,
                  ),
                )
                pendingCount++
              } else if (existingFile.modifiedTime != driveFile.modifiedTime ||
                existingFile.remoteMd5 != driveFile.md5Checksum
              ) {
                // Modified file
                logger.debug { "Modified file: ${driveFile.name}" }
                val fileRecord = fileOps.driveFileToRecord(driveFile)
                send(
                  SyncEvent.FileQueued(
                    fileRecord.id,
                    fileRecord.name,
                    fileRecord.size,
                  ),
                )
                pendingCount++
              }
            }
          }

          // Download pending files
          if (pendingCount > 0) {
            logger.info { "Downloading $pendingCount changed files..." }
            downloadPendingFiles(
              db = db,
              fileOps = fileOps,
              onProgress = { fileId, fileName, bytes, total ->
                send(SyncEvent.FileDownloading(fileId, fileName, bytes, total))
              },
              onCompleted = { fileId, fileName, bytes ->
                filesProcessed++
                bytesDownloaded += bytes
                send(SyncEvent.FileCompleted(fileId, fileName))
                send(
                  SyncEvent.Progress(
                    filesProcessed,
                    pendingCount,
                    bytesDownloaded,
                  ),
                )
              },
              onFailed = { fileId, fileName, error ->
                failedFiles++
                send(SyncEvent.FileFailed(fileId, fileName, error))
              },
            )
          }

          // Save new change token
          db.saveChangeToken(changeList.newStartPageToken, Instant.now())

          // Complete sync run
          val duration = Duration.between(startTime, Instant.now())
          db.updateSyncRunProgress(syncRunId, filesProcessed, bytesDownloaded)
          db.completeSyncRun(syncRunId, Instant.now(), "completed")

          send(
            SyncEvent.Completed(filesProcessed, bytesDownloaded, failedFiles, duration),
          )
        }
      } catch (e: Exception) {
        logger.error(e) { "Incremental sync failed" }
        send(SyncEvent.Failed("Incremental sync failed: ${e.message}"))
      }
    }

  override fun resumeSync(): Flow<SyncEvent> =
    channelFlow {
      val startTime = Instant.now()
      var filesProcessed = 0
      var bytesDownloaded = 0L
      var failedFiles = 0

      try {
        DatabaseManager(databasePath).use { db ->
          val fileOps =
            FileOperations(
              config.downloadDirectory,
              driveClient,
              config.exportFormats,
              db,
            )

          // Check for incomplete sync run
          val lastRun = db.getLastSyncRun()

          if (lastRun != null && (lastRun.status == "running" || lastRun.status == "interrupted")) {
            // Resume interrupted sync
            logger.info { "Resuming interrupted sync from run ${lastRun.id}..." }
            send(SyncEvent.Started(lastRun.id, startTime))

            // Get pending and downloading files
            val pendingFiles =
              db.getFilesByStatuses(
                listOf(
                  FileRecord.SyncStatus.PENDING,
                  FileRecord.SyncStatus.DOWNLOADING,
                ),
              )

            send(SyncEvent.DiscoveringFiles(pendingFiles.size))

            for (file in pendingFiles) {
              send(SyncEvent.FileQueued(file.id, file.name, file.size))
            }

            // Download pending files
            logger.info { "Downloading ${pendingFiles.size} pending files..." }
            downloadPendingFiles(
              db = db,
              fileOps = fileOps,
              onProgress = { fileId, fileName, bytes, total ->
                send(SyncEvent.FileDownloading(fileId, fileName, bytes, total))
              },
              onCompleted = { fileId, fileName, bytes ->
                filesProcessed++
                bytesDownloaded += bytes
                send(SyncEvent.FileCompleted(fileId, fileName))
                send(
                  SyncEvent.Progress(
                    filesProcessed,
                    pendingFiles.size,
                    bytesDownloaded,
                  ),
                )
              },
              onFailed = { fileId, fileName, error ->
                failedFiles++
                send(SyncEvent.FileFailed(fileId, fileName, error))
              },
            )

            // Complete sync run
            val duration = Duration.between(lastRun.startedAt, Instant.now())
            db.updateSyncRunProgress(
              lastRun.id,
              lastRun.filesProcessed + filesProcessed,
              lastRun.bytesDownloaded + bytesDownloaded,
            )
            db.completeSyncRun(lastRun.id, Instant.now(), "completed")

            send(
              SyncEvent.Completed(
                filesProcessed,
                bytesDownloaded,
                failedFiles,
                duration,
              ),
            )
          } else {
            // No interrupted sync, perform incremental sync
            logger.info { "No interrupted sync found, performing incremental sync..." }
            incrementalSync().collect { event ->
              send(event)
            }
          }
        }
      } catch (e: Exception) {
        logger.error(e) { "Resume sync failed" }
        send(SyncEvent.Failed("Resume sync failed: ${e.message}"))
      }
    }

  override suspend fun getSyncStatus(): Result<SyncStatus> {
    return try {
      DatabaseManager(databasePath).use { db ->
        val lastRun = db.getLastSyncRun()
        val stats = db.getSyncStatistics()

        Result.success(
          SyncStatus(
            lastSyncTime = lastRun?.completedAt,
            filesTracked = stats.totalFiles,
            totalSize = stats.totalSize,
            pendingFiles = stats.pendingFiles,
            failedFiles = stats.failedFiles,
          ),
        )
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to get sync status" }
      Result.failure(e)
    }
  }

  override suspend fun getFailedFiles(): Result<List<FileRecord>> {
    return try {
      DatabaseManager(databasePath).use { db ->
        val failedFiles = db.getFilesByStatus(FileRecord.SyncStatus.ERROR)
        Result.success(failedFiles)
      }
    } catch (e: Exception) {
      logger.error(e) { "Failed to get failed files" }
      Result.failure(e)
    }
  }

  /**
   * Download all pending files using concurrent downloads with breadth-first traversal.
   */
  private suspend fun downloadPendingFiles(
    db: DatabaseManager,
    fileOps: FileOperations,
    onProgress: suspend (String, String, Long, Long?) -> Unit,
    onCompleted: suspend (String, String, Long) -> Unit,
    onFailed: suspend (String, String, String) -> Unit,
  ) = withContext(Dispatchers.IO) {
    val semaphore = Semaphore(config.maxConcurrentDownloads)

    // Get all pending files
    val pendingFiles = db.getFilesByStatus(FileRecord.SyncStatus.PENDING)

    // Separate folders and files
    val folders = pendingFiles.filter { it.isFolder }
    val files = pendingFiles.filter { !it.isFolder }

    // Process folders first (breadth-first)
    for (folder in folders) {
      semaphore.acquire()
      try {
        db.updateFileStatus(folder.id, FileRecord.SyncStatus.DOWNLOADING)
        val result = fileOps.downloadFile(folder) { _, _ -> }

        if (result.isSuccess) {
          db.updateFileStatus(folder.id, FileRecord.SyncStatus.COMPLETE, Instant.now())
          onCompleted(folder.id, folder.name, 0)
        } else {
          val error = result.exceptionOrNull()?.message ?: "Unknown error"
          db.updateFileStatus(
            folder.id,
            FileRecord.SyncStatus.ERROR,
            errorMessage = error,
          )
          onFailed(folder.id, folder.name, error)
        }
      } finally {
        semaphore.release()
      }
    }

    // Process files concurrently
    coroutineScope {
      val jobs =
        files.map { file ->
          async {
            semaphore.acquire()
            try {
              db.updateFileStatus(file.id, FileRecord.SyncStatus.DOWNLOADING)

              var lastBytes = 0L
              val result =
                fileOps.downloadFile(file) { bytes, total ->
                  runBlocking {
                    onProgress(file.id, file.name, bytes, total)
                  }
                  lastBytes = bytes
                }

              if (result.isSuccess) {
                db.updateFileStatus(
                  file.id,
                  FileRecord.SyncStatus.COMPLETE,
                  Instant.now(),
                )
                onCompleted(file.id, file.name, file.size ?: lastBytes)
              } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                db.updateFileStatus(
                  file.id,
                  FileRecord.SyncStatus.ERROR,
                  errorMessage = error,
                )
                onFailed(file.id, file.name, error)
              }
            } finally {
              semaphore.release()
            }
          }
        }

      // Wait for all downloads to complete
      jobs.forEach { it.await() }
    }
  }
}
