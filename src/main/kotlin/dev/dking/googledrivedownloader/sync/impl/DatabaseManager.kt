package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.sync.FileRecord
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

/**
 * Manages SQLite database operations for sync state persistence.
 * Handles schema initialization and CRUD operations for files, sync runs, and change tokens.
 */
internal class DatabaseManager(databasePath: Path) : AutoCloseable {
  private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")

  init {
    initializeSchema()
  }

  /**
   * Initialize database schema if not exists.
   */
  private fun initializeSchema() {
    connection.createStatement().use { stmt ->
      // Create files table
      stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS files (
            id              TEXT PRIMARY KEY,
            name            TEXT NOT NULL,
            mime_type       TEXT NOT NULL,
            parent_id       TEXT,
            local_path      TEXT,
            remote_md5      TEXT,
            modified_time   TEXT NOT NULL,
            size            INTEGER,
            is_folder       INTEGER NOT NULL,
            sync_status     TEXT NOT NULL,
            last_synced     TEXT,
            error_message   TEXT
        )
        """.trimIndent(),
      )

      // Create sync_runs table
      stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS sync_runs (
            id                  INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at          TEXT NOT NULL,
            completed_at        TEXT,
            status              TEXT NOT NULL,
            files_processed     INTEGER DEFAULT 0,
            bytes_downloaded    INTEGER DEFAULT 0,
            start_page_token    TEXT,
            error_message       TEXT
        )
        """.trimIndent(),
      )

      // Create change_tokens table
      stmt.executeUpdate(
        """
        CREATE TABLE IF NOT EXISTS change_tokens (
            id              INTEGER PRIMARY KEY CHECK (id = 1),
            page_token      TEXT NOT NULL,
            updated_at      TEXT NOT NULL
        )
        """.trimIndent(),
      )

      // Create indexes
      stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_parent ON files(parent_id)")
      stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_status ON files(sync_status)")
    }
  }

  // ======================== Sync Run Operations ========================

  /**
   * Create a new sync run record.
   * @return The ID of the created sync run
   */
  fun createSyncRun(
    startedAt: Instant,
    startPageToken: String?,
  ): Long {
    val sql =
      """
      INSERT INTO sync_runs (started_at, status, start_page_token)
      VALUES (?, 'running', ?)
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, startedAt.toString())
      stmt.setString(2, startPageToken)
      stmt.executeUpdate()
    }

    // Get the last inserted ID
    connection.prepareStatement("SELECT last_insert_rowid()").use { stmt ->
      val rs = stmt.executeQuery()
      return if (rs.next()) {
        rs.getLong(
          1,
        )
      } else {
        throw IllegalStateException("Failed to get sync run ID")
      }
    }
  }

  /**
   * Update sync run progress.
   */
  fun updateSyncRunProgress(
    syncRunId: Long,
    filesProcessed: Int,
    bytesDownloaded: Long,
  ) {
    val sql =
      """
      UPDATE sync_runs
      SET files_processed = ?, bytes_downloaded = ?
      WHERE id = ?
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setInt(1, filesProcessed)
      stmt.setLong(2, bytesDownloaded)
      stmt.setLong(3, syncRunId)
      stmt.executeUpdate()
    }
  }

  /**
   * Mark sync run as completed.
   */
  fun completeSyncRun(
    syncRunId: Long,
    completedAt: Instant,
    status: String,
    errorMessage: String? = null,
  ) {
    val sql =
      """
      UPDATE sync_runs
      SET completed_at = ?, status = ?, error_message = ?
      WHERE id = ?
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, completedAt.toString())
      stmt.setString(2, status)
      stmt.setString(3, errorMessage)
      stmt.setLong(4, syncRunId)
      stmt.executeUpdate()
    }
  }

  /**
   * Get the last sync run.
   */
  fun getLastSyncRun(): SyncRunRecord? {
    val sql = "SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1"

    connection.prepareStatement(sql).use { stmt ->
      val rs = stmt.executeQuery()
      return if (rs.next()) {
        mapSyncRunRecord(rs)
      } else {
        null
      }
    }
  }

  // ======================== File Operations ========================

  /**
   * Insert or update a file record.
   */
  fun upsertFile(
    id: String,
    name: String,
    mimeType: String,
    parentId: String?,
    localPath: String?,
    remoteMd5: String?,
    modifiedTime: Instant,
    size: Long?,
    isFolder: Boolean,
    syncStatus: FileRecord.SyncStatus,
  ) {
    val sql =
      """
      INSERT OR REPLACE INTO files
      (id, name, mime_type, parent_id, local_path, remote_md5, modified_time, size, is_folder, sync_status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, id)
      stmt.setString(2, name)
      stmt.setString(3, mimeType)
      stmt.setString(4, parentId)
      stmt.setString(5, localPath)
      stmt.setString(6, remoteMd5)
      stmt.setString(7, modifiedTime.toString())
      stmt.setLong(8, size ?: 0)
      stmt.setInt(9, if (isFolder) 1 else 0)
      stmt.setString(10, syncStatus.name.lowercase())
      stmt.executeUpdate()
    }
  }

  /**
   * Update file sync status.
   */
  fun updateFileStatus(
    fileId: String,
    syncStatus: FileRecord.SyncStatus,
    lastSynced: Instant? = null,
    errorMessage: String? = null,
  ) {
    val sql =
      """
      UPDATE files
      SET sync_status = ?, last_synced = ?, error_message = ?
      WHERE id = ?
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, syncStatus.name.lowercase())
      stmt.setString(2, lastSynced?.toString())
      stmt.setString(3, errorMessage)
      stmt.setString(4, fileId)
      stmt.executeUpdate()
    }
  }

  /**
   * Get file record by ID.
   */
  fun getFile(fileId: String): FileRecord? {
    val sql = "SELECT * FROM files WHERE id = ?"

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, fileId)
      val rs = stmt.executeQuery()
      return if (rs.next()) {
        mapFileRecord(rs)
      } else {
        null
      }
    }
  }

  /**
   * Get all files with a specific sync status.
   */
  fun getFilesByStatus(status: FileRecord.SyncStatus): List<FileRecord> {
    val sql = "SELECT * FROM files WHERE sync_status = ?"
    val files = mutableListOf<FileRecord>()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, status.name.lowercase())
      val rs = stmt.executeQuery()
      while (rs.next()) {
        files.add(mapFileRecord(rs))
      }
    }

    return files
  }

  /**
   * Get all files with multiple sync statuses.
   */
  fun getFilesByStatuses(statuses: List<FileRecord.SyncStatus>): List<FileRecord> {
    if (statuses.isEmpty()) return emptyList()

    val placeholders = statuses.joinToString(",") { "?" }
    val sql = "SELECT * FROM files WHERE sync_status IN ($placeholders)"
    val files = mutableListOf<FileRecord>()

    connection.prepareStatement(sql).use { stmt ->
      statuses.forEachIndexed { index, status ->
        stmt.setString(index + 1, status.name.lowercase())
      }
      val rs = stmt.executeQuery()
      while (rs.next()) {
        files.add(mapFileRecord(rs))
      }
    }

    return files
  }

  /**
   * Get children of a parent folder.
   */
  fun getChildren(parentId: String?): List<FileRecord> {
    val sql =
      if (parentId == null) {
        "SELECT * FROM files WHERE parent_id IS NULL"
      } else {
        "SELECT * FROM files WHERE parent_id = ?"
      }

    val files = mutableListOf<FileRecord>()

    connection.prepareStatement(sql).use { stmt ->
      if (parentId != null) {
        stmt.setString(1, parentId)
      }
      val rs = stmt.executeQuery()
      while (rs.next()) {
        files.add(mapFileRecord(rs))
      }
    }

    return files
  }

  /**
   * Delete a file record.
   */
  fun deleteFile(fileId: String) {
    val sql = "DELETE FROM files WHERE id = ?"

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, fileId)
      stmt.executeUpdate()
    }
  }

  /**
   * Get sync statistics.
   */
  fun getSyncStatistics(): SyncStatistics {
    val sql =
      """
      SELECT
          COUNT(*) as total_files,
          SUM(size) as total_size,
          SUM(CASE WHEN sync_status = 'pending' THEN 1 ELSE 0 END) as pending_files,
          SUM(CASE WHEN sync_status = 'error' THEN 1 ELSE 0 END) as failed_files
      FROM files
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      val rs = stmt.executeQuery()
      if (rs.next()) {
        return SyncStatistics(
          totalFiles = rs.getInt("total_files"),
          totalSize = rs.getLong("total_size"),
          pendingFiles = rs.getInt("pending_files"),
          failedFiles = rs.getInt("failed_files"),
        )
      }
      return SyncStatistics(0, 0, 0, 0)
    }
  }

  // ======================== Change Token Operations ========================

  /**
   * Save or update the change token.
   */
  fun saveChangeToken(
    token: String,
    updatedAt: Instant,
  ) {
    val sql =
      """
      INSERT OR REPLACE INTO change_tokens (id, page_token, updated_at)
      VALUES (1, ?, ?)
      """.trimIndent()

    connection.prepareStatement(sql).use { stmt ->
      stmt.setString(1, token)
      stmt.setString(2, updatedAt.toString())
      stmt.executeUpdate()
    }
  }

  /**
   * Get the saved change token.
   */
  fun getChangeToken(): String? {
    val sql = "SELECT page_token FROM change_tokens WHERE id = 1"

    connection.prepareStatement(sql).use { stmt ->
      val rs = stmt.executeQuery()
      return if (rs.next()) {
        rs.getString("page_token")
      } else {
        null
      }
    }
  }

  // ======================== Helper Methods ========================

  private fun mapFileRecord(rs: ResultSet): FileRecord {
    return FileRecord(
      id = rs.getString("id"),
      name = rs.getString("name"),
      mimeType = rs.getString("mime_type"),
      parentId = rs.getString("parent_id"),
      localPath = rs.getString("local_path"),
      remoteMd5 = rs.getString("remote_md5"),
      modifiedTime = Instant.parse(rs.getString("modified_time")),
      size = rs.getLong("size").takeIf { !rs.wasNull() },
      isFolder = rs.getInt("is_folder") == 1,
      syncStatus = FileRecord.SyncStatus.valueOf(rs.getString("sync_status").uppercase()),
      lastSynced = rs.getString("last_synced")?.let { Instant.parse(it) },
      errorMessage = rs.getString("error_message"),
    )
  }

  private fun mapSyncRunRecord(rs: ResultSet): SyncRunRecord {
    return SyncRunRecord(
      id = rs.getLong("id"),
      startedAt = Instant.parse(rs.getString("started_at")),
      completedAt = rs.getString("completed_at")?.let { Instant.parse(it) },
      status = rs.getString("status"),
      filesProcessed = rs.getInt("files_processed"),
      bytesDownloaded = rs.getLong("bytes_downloaded"),
      startPageToken = rs.getString("start_page_token"),
      errorMessage = rs.getString("error_message"),
    )
  }

  override fun close() {
    connection.close()
  }
}

/**
 * Represents a sync run record from the database.
 */
internal data class SyncRunRecord(
  val id: Long,
  val startedAt: Instant,
  val completedAt: Instant?,
  val status: String,
  val filesProcessed: Int,
  val bytesDownloaded: Long,
  val startPageToken: String?,
  val errorMessage: String?,
)

/**
 * Sync statistics from the database.
 */
internal data class SyncStatistics(
  val totalFiles: Int,
  val totalSize: Long,
  val pendingFiles: Int,
  val failedFiles: Int,
)
