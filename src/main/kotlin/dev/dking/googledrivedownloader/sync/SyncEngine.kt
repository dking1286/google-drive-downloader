package dev.dking.googledrivedownloader.sync

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Core sync engine that orchestrates downloading files from Google Drive.
 * Emits progress events via Flow and manages local state persistence.
 */
interface SyncEngine {
  /**
   * Perform full initial sync, downloading all files from Google Drive.
   * Emits SyncEvent updates throughout the process.
   * @return Flow of sync events from start to completion or failure
   */
  fun initialSync(): Flow<SyncEvent>

  /**
   * Perform incremental sync based on detected changes since last sync.
   * Uses change tokens to efficiently detect modified/new/deleted files.
   * @return Flow of sync events from start to completion or failure
   */
  fun incrementalSync(): Flow<SyncEvent>

  /**
   * Resume an interrupted sync if one exists, otherwise perform incremental sync.
   * Checks database for incomplete sync run and continues from where it left off.
   * @return Flow of sync events from resume/start to completion or failure
   */
  fun resumeSync(): Flow<SyncEvent>

  /**
   * Get current sync status snapshot without performing a sync.
   * @return Result containing current SyncStatusSnapshot or error
   */
  suspend fun getSyncStatus(): Result<SyncStatusSnapshot>

  /**
   * Get list of files that failed to sync in the last run.
   * @return Result containing list of FileRecord with sync_status=ERROR or error
   */
  suspend fun getFailedFiles(): Result<List<FileRecord>>
}

/**
 * Events emitted during sync operations.
 */
sealed class SyncEvent {
  data class Started(val syncRunId: Long, val timestamp: Instant) : SyncEvent()

  data class DiscoveringFiles(val filesFound: Int) : SyncEvent()

  data class FileQueued(val fileId: String, val name: String, val size: Long?) : SyncEvent()

  data class FileDownloading(
    val fileId: String,
    val name: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
  ) : SyncEvent()

  data class FileCompleted(val fileId: String, val name: String) : SyncEvent()

  data class FileFailed(val fileId: String, val name: String, val error: String) : SyncEvent()

  data class Progress(
    val filesProcessed: Int,
    val totalFiles: Int,
    val bytesDownloaded: Long,
  ) : SyncEvent()

  data class Completed(
    val filesProcessed: Int,
    val bytesDownloaded: Long,
    val failedFiles: Int,
    val duration: Duration,
  ) : SyncEvent()

  data class Failed(val error: String) : SyncEvent()
}

/**
 * Current sync status snapshot.
 */
data class SyncStatusSnapshot(
  val lastSyncTime: Instant?,
  val filesTracked: Int,
  val totalSize: Long,
  val pendingFiles: Int,
  val failedFiles: Int,
)

/**
 * Represents a file record in the local state database.
 */
data class FileRecord(
  val id: String,
  val name: String,
  val mimeType: String,
  val parentId: String?,
  val localPath: String?,
  val remoteMd5: String?,
  val modifiedTime: Instant,
  val size: Long?,
  val isFolder: Boolean,
  val syncStatus: FileRecord.SyncStatus,
  val lastSynced: Instant?,
  val errorMessage: String?,
) {
  enum class SyncStatus {
    PENDING,
    DOWNLOADING,
    COMPLETE,
    ERROR,
  }
}

/**
 * Configuration for the Sync Engine.
 */
data class SyncEngineConfig(
  val downloadDirectory: Path,
  val exportFormats: Map<String, String>,
  val maxConcurrentDownloads: Int = 4,
  val deleteRemovedFiles: Boolean = false,
)
