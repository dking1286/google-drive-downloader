package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.sync.FileRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseManagerTest {
  private lateinit var tempDir: Path
  private lateinit var databasePath: Path
  private lateinit var db: DatabaseManager

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("database-manager-test")
    databasePath = tempDir.resolve("test.db")
    db = DatabaseManager(databasePath)
  }

  @AfterTest
  fun tearDown() {
    db.close()
    tempDir.toFile().deleteRecursively()
  }

  // ======================== Schema Initialization Tests ========================

  @Test
  fun `schema initialization creates files table`() {
    // Verify we can insert into files table (schema created correctly)
    val now = Instant.now()
    db.upsertFile(
      id = "test-id",
      name = "test.txt",
      mimeType = "text/plain",
      parentId = null,
      localPath = "test.txt",
      remoteMd5 = "abc123",
      modifiedTime = now,
      size = 100L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    val file = db.getFile("test-id")
    assertNotNull(file)
    assertEquals("test.txt", file.name)
  }

  @Test
  fun `schema initialization creates sync_runs table`() {
    // Verify we can create sync runs (schema created correctly)
    val id = db.createSyncRun(Instant.now(), "token123")
    assertTrue(id > 0)

    val lastRun = db.getLastSyncRun()
    assertNotNull(lastRun)
    assertEquals(id, lastRun.id)
  }

  @Test
  fun `schema initialization creates change_tokens table`() {
    // Verify we can save and retrieve change tokens
    val now = Instant.now()
    db.saveChangeToken("token123", now)

    val token = db.getChangeToken()
    assertEquals("token123", token)
  }

  // ======================== Sync Run Operations Tests ========================

  @Test
  fun `createSyncRun returns valid ID`() {
    val id = db.createSyncRun(Instant.now(), "start-token")

    assertTrue(id > 0)
  }

  @Test
  fun `createSyncRun increments IDs`() {
    val id1 = db.createSyncRun(Instant.now(), "token1")
    db.completeSyncRun(id1, Instant.now(), "completed")

    val id2 = db.createSyncRun(Instant.now(), "token2")

    assertTrue(id2 > id1)
  }

  @Test
  fun `getLastSyncRun retrieves most recent sync run`() {
    val now = Instant.now()
    db.createSyncRun(now.minusSeconds(100), "token1")
    val id2 = db.createSyncRun(now, "token2")

    val lastRun = db.getLastSyncRun()

    assertNotNull(lastRun)
    assertEquals(id2, lastRun.id)
    assertEquals("running", lastRun.status)
    assertEquals("token2", lastRun.startPageToken)
  }

  @Test
  fun `getLastSyncRun returns null when no sync runs exist`() {
    val lastRun = db.getLastSyncRun()

    assertNull(lastRun)
  }

  @Test
  fun `updateSyncRunProgress updates file and byte counts`() {
    val id = db.createSyncRun(Instant.now(), null)

    db.updateSyncRunProgress(id, filesProcessed = 10, bytesDownloaded = 5000L)

    val lastRun = db.getLastSyncRun()
    assertNotNull(lastRun)
    assertEquals(10, lastRun.filesProcessed)
    assertEquals(5000L, lastRun.bytesDownloaded)
  }

  @Test
  fun `completeSyncRun updates status and completion time`() {
    val startTime = Instant.now()
    val id = db.createSyncRun(startTime, null)
    val completionTime = startTime.plusSeconds(60)

    db.completeSyncRun(id, completionTime, "completed")

    val lastRun = db.getLastSyncRun()
    assertNotNull(lastRun)
    assertEquals("completed", lastRun.status)
    assertEquals(completionTime, lastRun.completedAt)
  }

  @Test
  fun `completeSyncRun with error message stores the error`() {
    val id = db.createSyncRun(Instant.now(), null)

    db.completeSyncRun(id, Instant.now(), "failed", "Network error")

    val lastRun = db.getLastSyncRun()
    assertNotNull(lastRun)
    assertEquals("failed", lastRun.status)
    assertEquals("Network error", lastRun.errorMessage)
  }

  // ======================== File Operations Tests ========================

  @Test
  fun `upsertFile inserts new file`() {
    val now = Instant.now()

    db.upsertFile(
      id = "file1",
      name = "document.pdf",
      mimeType = "application/pdf",
      parentId = "folder1",
      localPath = "folder1/document.pdf",
      remoteMd5 = "md5hash",
      modifiedTime = now,
      size = 1024L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    val file = db.getFile("file1")
    assertNotNull(file)
    assertEquals("file1", file.id)
    assertEquals("document.pdf", file.name)
    assertEquals("application/pdf", file.mimeType)
    assertEquals("folder1", file.parentId)
    assertEquals("folder1/document.pdf", file.localPath)
    assertEquals("md5hash", file.remoteMd5)
    assertEquals(now, file.modifiedTime)
    assertEquals(1024L, file.size)
    assertEquals(false, file.isFolder)
    assertEquals(FileRecord.SyncStatus.PENDING, file.syncStatus)
  }

  @Test
  fun `upsertFile updates existing file`() {
    val now = Instant.now()

    // Insert initial file
    db.upsertFile(
      id = "file1",
      name = "old-name.txt",
      mimeType = "text/plain",
      parentId = null,
      localPath = "old-name.txt",
      remoteMd5 = "old-md5",
      modifiedTime = now,
      size = 100L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    // Update same file
    val laterTime = now.plusSeconds(60)
    db.upsertFile(
      id = "file1",
      name = "new-name.txt",
      mimeType = "text/plain",
      parentId = "folder1",
      localPath = "folder1/new-name.txt",
      remoteMd5 = "new-md5",
      modifiedTime = laterTime,
      size = 200L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.COMPLETE,
    )

    val file = db.getFile("file1")
    assertNotNull(file)
    assertEquals("new-name.txt", file.name)
    assertEquals("folder1", file.parentId)
    assertEquals("folder1/new-name.txt", file.localPath)
    assertEquals("new-md5", file.remoteMd5)
    assertEquals(laterTime, file.modifiedTime)
    assertEquals(200L, file.size)
  }

  @Test
  fun `upsertFile handles folder correctly`() {
    db.upsertFile(
      id = "folder1",
      name = "My Folder",
      mimeType = "application/vnd.google-apps.folder",
      parentId = null,
      localPath = "My Folder",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = null,
      isFolder = true,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    val folder = db.getFile("folder1")
    assertNotNull(folder)
    assertEquals(true, folder.isFolder)
    assertNull(folder.remoteMd5)
  }

  @Test
  fun `updateFileStatus updates status only`() {
    db.upsertFile(
      id = "file1",
      name = "test.txt",
      mimeType = "text/plain",
      parentId = null,
      localPath = "test.txt",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = 100L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.PENDING,
    )

    db.updateFileStatus("file1", FileRecord.SyncStatus.DOWNLOADING)

    val file = db.getFile("file1")
    assertNotNull(file)
    assertEquals(FileRecord.SyncStatus.DOWNLOADING, file.syncStatus)
    assertNull(file.lastSynced)
    assertNull(file.errorMessage)
  }

  @Test
  fun `updateFileStatus with lastSynced time`() {
    val syncTime = Instant.now()
    db.upsertFile(
      id = "file1",
      name = "test.txt",
      mimeType = "text/plain",
      parentId = null,
      localPath = "test.txt",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = 100L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.DOWNLOADING,
    )

    db.updateFileStatus("file1", FileRecord.SyncStatus.COMPLETE, syncTime)

    val file = db.getFile("file1")
    assertNotNull(file)
    assertEquals(FileRecord.SyncStatus.COMPLETE, file.syncStatus)
    assertEquals(syncTime, file.lastSynced)
  }

  @Test
  fun `updateFileStatus with error message`() {
    db.upsertFile(
      id = "file1",
      name = "test.txt",
      mimeType = "text/plain",
      parentId = null,
      localPath = "test.txt",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = 100L,
      isFolder = false,
      syncStatus = FileRecord.SyncStatus.DOWNLOADING,
    )

    db.updateFileStatus("file1", FileRecord.SyncStatus.ERROR, errorMessage = "Download failed")

    val file = db.getFile("file1")
    assertNotNull(file)
    assertEquals(FileRecord.SyncStatus.ERROR, file.syncStatus)
    assertEquals("Download failed", file.errorMessage)
  }

  @Test
  fun `getFile returns null for non-existing file`() {
    val file = db.getFile("non-existent-id")

    assertNull(file)
  }

  @Test
  fun `getFilesByStatus returns matching files`() {
    val now = Instant.now()
    db.upsertFile(
      "f1", "file1.txt", "text/plain", null, "file1.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f2", "file2.txt", "text/plain", null, "file2.txt", null,
      now, 200L, false, FileRecord.SyncStatus.COMPLETE,
    )
    db.upsertFile(
      "f3", "file3.txt", "text/plain", null, "file3.txt", null,
      now, 300L, false, FileRecord.SyncStatus.PENDING,
    )

    val pendingFiles = db.getFilesByStatus(FileRecord.SyncStatus.PENDING)

    assertEquals(2, pendingFiles.size)
    assertTrue(pendingFiles.all { it.syncStatus == FileRecord.SyncStatus.PENDING })
  }

  @Test
  fun `getFilesByStatus returns empty list when no matches`() {
    val files = db.getFilesByStatus(FileRecord.SyncStatus.ERROR)

    assertTrue(files.isEmpty())
  }

  @Test
  fun `getFilesByStatuses returns files matching any status`() {
    val now = Instant.now()
    db.upsertFile(
      "f1", "file1.txt", "text/plain", null, "file1.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f2", "file2.txt", "text/plain", null, "file2.txt", null,
      now, 200L, false, FileRecord.SyncStatus.DOWNLOADING,
    )
    db.upsertFile(
      "f3", "file3.txt", "text/plain", null, "file3.txt", null,
      now, 300L, false, FileRecord.SyncStatus.COMPLETE,
    )

    val files =
      db.getFilesByStatuses(
        listOf(FileRecord.SyncStatus.PENDING, FileRecord.SyncStatus.DOWNLOADING),
      )

    assertEquals(2, files.size)
    assertTrue(files.none { it.syncStatus == FileRecord.SyncStatus.COMPLETE })
  }

  @Test
  fun `getFilesByStatuses returns empty list for empty status list`() {
    val files = db.getFilesByStatuses(emptyList())

    assertTrue(files.isEmpty())
  }

  @Test
  fun `getChildren returns files with matching parentId`() {
    val now = Instant.now()
    db.upsertFile(
      "folder1", "Folder", "application/vnd.google-apps.folder", null, "Folder", null,
      now, null, true, FileRecord.SyncStatus.COMPLETE,
    )
    db.upsertFile(
      "f1", "file1.txt", "text/plain", "folder1", "Folder/file1.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f2", "file2.txt", "text/plain", "folder1", "Folder/file2.txt", null,
      now, 200L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f3", "file3.txt", "text/plain", null, "file3.txt", null,
      now, 300L, false, FileRecord.SyncStatus.PENDING,
    )

    val children = db.getChildren("folder1")

    assertEquals(2, children.size)
    assertTrue(children.all { it.parentId == "folder1" })
  }

  @Test
  fun `getChildren with null parentId returns root files`() {
    val now = Instant.now()
    db.upsertFile(
      "folder1", "Folder", "application/vnd.google-apps.folder", null, "Folder", null,
      now, null, true, FileRecord.SyncStatus.COMPLETE,
    )
    db.upsertFile(
      "f1", "file1.txt", "text/plain", "folder1", "Folder/file1.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f2", "root-file.txt", "text/plain", null, "root-file.txt", null,
      now, 200L, false, FileRecord.SyncStatus.PENDING,
    )

    val rootFiles = db.getChildren(null)

    assertEquals(2, rootFiles.size) // folder1 and f2
    assertTrue(rootFiles.all { it.parentId == null })
  }

  @Test
  fun `deleteFile removes file record`() {
    val now = Instant.now()
    db.upsertFile(
      "file1", "test.txt", "text/plain", null, "test.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )

    db.deleteFile("file1")

    val file = db.getFile("file1")
    assertNull(file)
  }

  @Test
  fun `deleteFile does nothing for non-existent file`() {
    // Should not throw
    db.deleteFile("non-existent-id")
  }

  // ======================== Sync Statistics Tests ========================

  @Test
  fun `getSyncStatistics returns correct aggregation`() {
    val now = Instant.now()
    db.upsertFile(
      "f1", "file1.txt", "text/plain", null, "file1.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.upsertFile(
      "f2", "file2.txt", "text/plain", null, "file2.txt", null,
      now, 200L, false, FileRecord.SyncStatus.COMPLETE,
    )
    db.upsertFile(
      "f3", "file3.txt", "text/plain", null, "file3.txt", null,
      now, 300L, false, FileRecord.SyncStatus.ERROR,
    )
    db.upsertFile(
      "f4", "file4.txt", "text/plain", null, "file4.txt", null,
      now, 400L, false, FileRecord.SyncStatus.PENDING,
    )

    val stats = db.getSyncStatistics()

    assertEquals(4, stats.totalFiles)
    assertEquals(1000L, stats.totalSize) // 100 + 200 + 300 + 400
    assertEquals(2, stats.pendingFiles)
    assertEquals(1, stats.failedFiles)
  }

  @Test
  fun `getSyncStatistics returns zeros when no files`() {
    val stats = db.getSyncStatistics()

    assertEquals(0, stats.totalFiles)
    assertEquals(0L, stats.totalSize)
    assertEquals(0, stats.pendingFiles)
    assertEquals(0, stats.failedFiles)
  }

  // ======================== Change Token Tests ========================

  @Test
  fun `saveChangeToken and getChangeToken round-trip`() {
    val now = Instant.now()

    db.saveChangeToken("my-token-123", now)

    val token = db.getChangeToken()
    assertEquals("my-token-123", token)
  }

  @Test
  fun `saveChangeToken replaces existing token`() {
    val now = Instant.now()

    db.saveChangeToken("token-v1", now)
    db.saveChangeToken("token-v2", now.plusSeconds(60))

    val token = db.getChangeToken()
    assertEquals("token-v2", token)
  }

  @Test
  fun `getChangeToken returns null when no token saved`() {
    val token = db.getChangeToken()

    assertNull(token)
  }

  // ======================== Connection Lifecycle Tests ========================

  @Test
  fun `close releases database connection`() {
    db.close()

    // Create a new connection to verify database is accessible
    val newDb = DatabaseManager(databasePath)
    val stats = newDb.getSyncStatistics()
    assertEquals(0, stats.totalFiles)
    newDb.close()
  }

  @Test
  fun `database persists data after close and reopen`() {
    val now = Instant.now()
    db.upsertFile(
      "file1", "test.txt", "text/plain", null, "test.txt", null,
      now, 100L, false, FileRecord.SyncStatus.PENDING,
    )
    db.close()

    val newDb = DatabaseManager(databasePath)
    val file = newDb.getFile("file1")
    assertNotNull(file)
    assertEquals("test.txt", file.name)
    newDb.close()
  }
}
