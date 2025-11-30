package dev.dking.googledrivedownloader.sync.impl

import dev.dking.googledrivedownloader.api.DriveFile
import dev.dking.googledrivedownloader.api.GoogleDriveClient
import dev.dking.googledrivedownloader.files.PathValidationException
import dev.dking.googledrivedownloader.sync.FileRecord
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOperationsTest {
  private lateinit var tempDir: Path
  private lateinit var downloadDir: Path
  private lateinit var databasePath: Path
  private lateinit var driveClient: GoogleDriveClient
  private lateinit var databaseManager: DatabaseManager
  private lateinit var fileOps: FileOperations

  private val exportFormats =
    mapOf(
      "application/vnd.google-apps.document" to
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.google-apps.spreadsheet" to
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.google-apps.presentation" to
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("file-operations-test")
    downloadDir = tempDir.resolve("downloads")
    Files.createDirectories(downloadDir)
    databasePath = tempDir.resolve("test.db")

    driveClient = mockk()
    databaseManager = DatabaseManager(databasePath)
    fileOps = FileOperations(downloadDir, driveClient, exportFormats, databaseManager)
  }

  @AfterTest
  fun tearDown() {
    databaseManager.close()
    tempDir.toFile().deleteRecursively()
  }

  // ======================== sanitizeFilename Tests ========================

  @Test
  fun `sanitizeFilename replaces forward slashes`() {
    val result = fileOps.sanitizeFilename("path/to/file.txt")

    assertEquals("path_to_file.txt", result)
  }

  @Test
  fun `sanitizeFilename replaces null bytes`() {
    val result = fileOps.sanitizeFilename("file\u0000name.txt")

    assertEquals("file_name.txt", result)
  }

  @Test
  fun `sanitizeFilename preserves normal characters`() {
    val result = fileOps.sanitizeFilename("My Document (1).pdf")

    assertEquals("My Document (1).pdf", result)
  }

  @Test
  fun `sanitizeFilename truncates to 255 bytes`() {
    val longName = "a".repeat(300) + ".txt"

    val result = fileOps.sanitizeFilename(longName)

    assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
  }

  @Test
  fun `sanitizeFilename handles UTF-8 multi-byte characters`() {
    // Japanese characters are 3 bytes each in UTF-8
    val unicodeName = "日本語ファイル名.txt" // 9 chars * ~3 bytes + 4 bytes = ~31 bytes

    val result = fileOps.sanitizeFilename(unicodeName)

    // Should preserve valid UTF-8
    assertTrue(result.isNotEmpty())
    assertFalse(result.contains("\uFFFD")) // No replacement characters for short names
  }

  @Test
  fun `sanitizeFilename truncates UTF-8 without breaking characters`() {
    // Create a name that's >255 bytes with multi-byte chars
    val longUnicodeName = "日本語".repeat(100) // 100 * 3 chars * 3 bytes = 900 bytes

    val result = fileOps.sanitizeFilename(longUnicodeName)

    assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
  }

  // ======================== buildLocalPath Tests ========================

  @Test
  fun `buildLocalPath returns filename for root file`() {
    val path = fileOps.buildLocalPath("file1", "document.pdf", "application/pdf", null)

    assertEquals("document.pdf", path)
  }

  @Test
  fun `buildLocalPath includes parent folder`() {
    // Insert parent folder into database first
    databaseManager.upsertFile(
      id = "folder1",
      name = "My Folder",
      mimeType = "application/vnd.google-apps.folder",
      parentId = null,
      localPath = "My Folder",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = null,
      isFolder = true,
      syncStatus = FileRecord.SyncStatus.COMPLETE,
    )

    val path = fileOps.buildLocalPath("file1", "document.pdf", "application/pdf", "folder1")

    assertEquals("My Folder/document.pdf", path)
  }

  @Test
  fun `buildLocalPath handles nested folders`() {
    // Insert folder hierarchy
    databaseManager.upsertFile(
      id = "folder1",
      name = "Level1",
      mimeType = "application/vnd.google-apps.folder",
      parentId = null,
      localPath = "Level1",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = null,
      isFolder = true,
      syncStatus = FileRecord.SyncStatus.COMPLETE,
    )
    databaseManager.upsertFile(
      id = "folder2",
      name = "Level2",
      mimeType = "application/vnd.google-apps.folder",
      parentId = "folder1",
      localPath = "Level1/Level2",
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = null,
      isFolder = true,
      syncStatus = FileRecord.SyncStatus.COMPLETE,
    )

    val path =
      fileOps.buildLocalPath(
        "file1",
        "document.pdf",
        "application/pdf",
        "folder2",
      )

    assertEquals("Level1/Level2/document.pdf", path)
  }

  @Test
  fun `buildLocalPath adds docx extension for Google Docs`() {
    val path =
      fileOps.buildLocalPath(
        "doc1",
        "My Document",
        "application/vnd.google-apps.document",
        null,
      )

    assertEquals("My Document.docx", path)
  }

  @Test
  fun `buildLocalPath adds xlsx extension for Google Sheets`() {
    val path =
      fileOps.buildLocalPath(
        "sheet1",
        "My Spreadsheet",
        "application/vnd.google-apps.spreadsheet",
        null,
      )

    assertEquals("My Spreadsheet.xlsx", path)
  }

  @Test
  fun `buildLocalPath adds pptx extension for Google Slides`() {
    val path =
      fileOps.buildLocalPath(
        "slides1",
        "My Presentation",
        "application/vnd.google-apps.presentation",
        null,
      )

    assertEquals("My Presentation.pptx", path)
  }

  @Test
  fun `buildLocalPath does not duplicate extension if already present`() {
    val path =
      fileOps.buildLocalPath(
        "doc1",
        "My Document.docx",
        "application/vnd.google-apps.document",
        null,
      )

    assertEquals("My Document.docx", path)
  }

  @Test
  fun `buildLocalPath sanitizes filename with slashes`() {
    val path =
      fileOps.buildLocalPath(
        "file1",
        "path/to/file.txt",
        "text/plain",
        null,
      )

    assertEquals("path_to_file.txt", path)
  }

  // ======================== resolvePathConflict Tests ========================

  @Test
  fun `resolvePathConflict returns original path when no conflict`() {
    val originalPath = downloadDir.resolve("newfile.txt")

    val result = fileOps.resolvePathConflict(originalPath)

    assertEquals(originalPath, result)
  }

  @Test
  fun `resolvePathConflict adds number suffix for single conflict`() {
    val originalPath = downloadDir.resolve("existing.txt")
    Files.createFile(originalPath)

    val result = fileOps.resolvePathConflict(originalPath)

    assertEquals(downloadDir.resolve("existing (1).txt"), result)
  }

  @Test
  fun `resolvePathConflict increments number for multiple conflicts`() {
    Files.createFile(downloadDir.resolve("file.txt"))
    Files.createFile(downloadDir.resolve("file (1).txt"))
    Files.createFile(downloadDir.resolve("file (2).txt"))

    val result = fileOps.resolvePathConflict(downloadDir.resolve("file.txt"))

    assertEquals(downloadDir.resolve("file (3).txt"), result)
  }

  @Test
  fun `resolvePathConflict handles files without extension`() {
    Files.createFile(downloadDir.resolve("README"))

    val result = fileOps.resolvePathConflict(downloadDir.resolve("README"))

    assertEquals(downloadDir.resolve("README (1)"), result)
  }

  @Test
  fun `resolvePathConflict handles files with multiple dots`() {
    Files.createFile(downloadDir.resolve("archive.tar.gz"))

    val result = fileOps.resolvePathConflict(downloadDir.resolve("archive.tar.gz"))

    assertEquals(downloadDir.resolve("archive.tar (1).gz"), result)
  }

  // ======================== createFolder Tests ========================

  @Test
  fun `createFolder creates directory`() =
    runTest {
      val record = createFileRecord("folder1", "TestFolder", isFolder = true)

      val result = fileOps.createFolder(record)

      assertTrue(result.isSuccess)
      assertTrue(Files.isDirectory(downloadDir.resolve("TestFolder")))
    }

  @Test
  fun `createFolder creates nested directories`() =
    runTest {
      val record =
        createFileRecord(
          "folder1",
          "Nested",
          localPath = "Parent/Child/Nested",
          isFolder = true,
        )

      val result = fileOps.createFolder(record)

      assertTrue(result.isSuccess)
      assertTrue(Files.isDirectory(downloadDir.resolve("Parent/Child/Nested")))
    }

  @Test
  fun `createFolder fails without localPath`() =
    runTest {
      val record =
        FileRecord(
          id = "folder1",
          name = "NoPath",
          mimeType = "application/vnd.google-apps.folder",
          parentId = null,
          localPath = null,
          remoteMd5 = null,
          modifiedTime = Instant.now(),
          size = null,
          isFolder = true,
          syncStatus = FileRecord.SyncStatus.PENDING,
          lastSynced = null,
          errorMessage = null,
        )

      val result = fileOps.createFolder(record)

      assertTrue(result.isFailure)
      assertIs<IllegalStateException>(result.exceptionOrNull())
    }

  @Test
  fun `createFolder rejects path traversal attempt`() =
    runTest {
      val record =
        createFileRecord(
          "folder1",
          "Malicious",
          localPath = "../../../etc",
          isFolder = true,
        )

      val result = fileOps.createFolder(record)

      assertTrue(result.isFailure)
      assertIs<PathValidationException>(result.exceptionOrNull())
    }

  // ======================== downloadFile routing Tests ========================

  @Test
  fun `downloadFile routes folder to createFolder`() =
    runTest {
      val record = createFileRecord("folder1", "MyFolder", isFolder = true)

      val result = fileOps.downloadFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      assertTrue(Files.isDirectory(downloadDir.resolve("MyFolder")))
    }

  @Test
  fun `downloadFile routes shortcut to skipShortcut`() =
    runTest {
      val record =
        createFileRecord(
          "shortcut1",
          "My Shortcut",
          mimeType = "application/vnd.google-apps.shortcut",
        )

      val result = fileOps.downloadFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      // Verify no file was created (shortcuts are skipped)
      assertFalse(Files.exists(downloadDir.resolve("My Shortcut")))
    }

  @Test
  fun `downloadFile routes workspace file to exportWorkspaceFile`() =
    runTest {
      val record =
        createFileRecord(
          "doc1",
          "Document.docx",
          mimeType = "application/vnd.google-apps.document",
        )

      // Mock the export
      coEvery {
        driveClient.exportFile(
          "doc1",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          any(),
        )
      } coAnswers {
        val outputPath = thirdArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "exported content")
        Result.success(Unit)
      }

      val result = fileOps.downloadFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
    }

  @Test
  fun `downloadFile routes regular file to downloadRegularFile`() =
    runTest {
      val record =
        createFileRecord(
          "file1",
          "document.pdf",
          mimeType = "application/pdf",
        )

      // Mock the download
      coEvery { driveClient.downloadFile("file1", any(), any()) } coAnswers {
        val outputPath = secondArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "file content")
        Result.success(Unit)
      }

      val result = fileOps.downloadFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
    }

  // ======================== downloadRegularFile Tests ========================

  @Test
  fun `downloadRegularFile success downloads and renames atomically`() =
    runTest {
      val record = createFileRecord("file1", "test.txt", mimeType = "text/plain")

      coEvery { driveClient.downloadFile("file1", any(), any()) } coAnswers {
        val outputPath = secondArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "downloaded content")
        Result.success(Unit)
      }

      val result = fileOps.downloadRegularFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      assertTrue(Files.exists(downloadDir.resolve("test.txt")))
      assertEquals("downloaded content", Files.readString(downloadDir.resolve("test.txt")))
    }

  @Test
  fun `downloadRegularFile cleans up temp file on failure`() =
    runTest {
      val record = createFileRecord("file1", "test.txt", mimeType = "text/plain")

      coEvery { driveClient.downloadFile("file1", any(), any()) } returns
        Result.failure(Exception("Network error"))

      val result = fileOps.downloadRegularFile(record) { _, _ -> }

      assertTrue(result.isFailure)
      // Verify no temp files left behind
      val tempFiles = Files.list(downloadDir).filter { it.toString().endsWith(".tmp") }.count()
      assertEquals(0, tempFiles)
    }

  @Test
  fun `downloadRegularFile rejects path traversal`() =
    runTest {
      val record =
        createFileRecord(
          "file1",
          "malicious.txt",
          localPath = "../../../etc/passwd",
          mimeType = "text/plain",
        )

      val result = fileOps.downloadRegularFile(record) { _, _ -> }

      assertTrue(result.isFailure)
      assertIs<PathValidationException>(result.exceptionOrNull())
    }

  // ======================== exportWorkspaceFile Tests ========================

  @Test
  fun `exportWorkspaceFile exports with correct MIME type`() =
    runTest {
      val record =
        createFileRecord(
          "doc1",
          "Document.docx",
          mimeType = "application/vnd.google-apps.document",
        )

      var capturedMimeType: String? = null
      coEvery { driveClient.exportFile("doc1", any(), any()) } coAnswers {
        capturedMimeType = secondArg()
        val outputPath = thirdArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "exported")
        Result.success(Unit)
      }

      val result = fileOps.exportWorkspaceFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        capturedMimeType,
      )
    }

  @Test
  fun `exportWorkspaceFile fails for unconfigured MIME type`() =
    runTest {
      // application/vnd.google-apps.drawing is not in exportFormats
      val record =
        createFileRecord(
          "drawing1",
          "Drawing",
          mimeType = "application/vnd.google-apps.drawing",
        )

      val result = fileOps.exportWorkspaceFile(record) { _, _ -> }

      assertTrue(result.isFailure)
      assertIs<IllegalStateException>(result.exceptionOrNull())
    }

  // ======================== driveFileToRecord Tests ========================

  @Test
  fun `driveFileToRecord maps all fields correctly`() {
    val now = Instant.now()
    val driveFile =
      DriveFile(
        id = "file1",
        name = "test.pdf",
        mimeType = "application/pdf",
        parentId = null,
        md5Checksum = "abc123",
        modifiedTime = now,
        size = 1024L,
        isFolder = false,
        shortcutTargetId = null,
      )

    val record = fileOps.driveFileToRecord(driveFile)

    assertEquals("file1", record.id)
    assertEquals("test.pdf", record.name)
    assertEquals("application/pdf", record.mimeType)
    assertNull(record.parentId)
    assertEquals("test.pdf", record.localPath)
    assertEquals("abc123", record.remoteMd5)
    assertEquals(now, record.modifiedTime)
    assertEquals(1024L, record.size)
    assertEquals(false, record.isFolder)
    assertEquals(FileRecord.SyncStatus.PENDING, record.syncStatus)
    assertNull(record.lastSynced)
    assertNull(record.errorMessage)
  }

  @Test
  fun `driveFileToRecord inserts record into database`() {
    val driveFile =
      DriveFile(
        id = "file1",
        name = "test.pdf",
        mimeType = "application/pdf",
        parentId = null,
        md5Checksum = "abc123",
        modifiedTime = Instant.now(),
        size = 1024L,
        isFolder = false,
        shortcutTargetId = null,
      )

    fileOps.driveFileToRecord(driveFile)

    val dbRecord = databaseManager.getFile("file1")
    assertNotNull(dbRecord)
    assertEquals("test.pdf", dbRecord!!.name)
  }

  @Test
  fun `driveFileToRecord builds correct path for workspace file`() {
    val driveFile =
      DriveFile(
        id = "doc1",
        name = "My Document",
        mimeType = "application/vnd.google-apps.document",
        parentId = null,
        md5Checksum = null,
        modifiedTime = Instant.now(),
        size = null,
        isFolder = false,
        shortcutTargetId = null,
      )

    val record = fileOps.driveFileToRecord(driveFile)

    assertEquals("My Document.docx", record.localPath)
  }

  // ======================== Path Validation Integration Tests ========================

  @Test
  fun `createFolder rejects symlink in path`() =
    runTest {
      // Create a symlink pointing outside the download directory
      val targetOutside = tempDir.resolve("outside")
      Files.createDirectories(targetOutside)
      val symlink = downloadDir.resolve("symlink")
      Files.createSymbolicLink(symlink, targetOutside)

      val record =
        createFileRecord(
          "folder1",
          "MaliciousFolder",
          localPath = "symlink/nested",
          isFolder = true,
        )

      val result = fileOps.createFolder(record)

      assertTrue(result.isFailure)
      assertIs<PathValidationException>(result.exceptionOrNull())
    }

  // ======================== Path Conflict Resolution Tests ========================

  @Test
  fun `downloadRegularFile resolves conflict when file already exists`() =
    runTest {
      // Create an existing file
      val existingFile = downloadDir.resolve("test.txt")
      Files.writeString(existingFile, "existing content")

      val record = createFileRecord("file1", "test.txt", mimeType = "text/plain")

      coEvery { driveClient.downloadFile("file1", any(), any()) } coAnswers {
        val outputPath = secondArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "new content")
        Result.success(Unit)
      }

      val result = fileOps.downloadRegularFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      // Original file should still have original content
      assertEquals("existing content", Files.readString(existingFile))
      // New file should be created with (1) suffix
      val conflictFile = downloadDir.resolve("test (1).txt")
      assertTrue(Files.exists(conflictFile))
      assertEquals("new content", Files.readString(conflictFile))
    }

  @Test
  fun `exportWorkspaceFile resolves conflict when file already exists`() =
    runTest {
      // Create an existing file
      val existingFile = downloadDir.resolve("Document.docx")
      Files.writeString(existingFile, "existing content")

      val record =
        createFileRecord(
          "doc1",
          "Document.docx",
          mimeType = "application/vnd.google-apps.document",
        )

      coEvery {
        driveClient.exportFile(
          "doc1",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          any(),
        )
      } coAnswers {
        val outputPath = thirdArg<Path>()
        Files.createDirectories(outputPath.parent)
        Files.writeString(outputPath, "exported content")
        Result.success(Unit)
      }

      val result = fileOps.exportWorkspaceFile(record) { _, _ -> }

      assertTrue(result.isSuccess)
      // Original file should still have original content
      assertEquals("existing content", Files.readString(existingFile))
      // New file should be created with (1) suffix
      val conflictFile = downloadDir.resolve("Document (1).docx")
      assertTrue(Files.exists(conflictFile))
      assertEquals("exported content", Files.readString(conflictFile))
    }

  // ======================== Helper Methods ========================

  private fun createFileRecord(
    id: String,
    name: String,
    localPath: String? = name,
    mimeType: String = "application/vnd.google-apps.folder",
    isFolder: Boolean = false,
  ): FileRecord {
    return FileRecord(
      id = id,
      name = name,
      mimeType = mimeType,
      parentId = null,
      localPath = localPath,
      remoteMd5 = null,
      modifiedTime = Instant.now(),
      size = if (isFolder) null else 100L,
      isFolder = isFolder,
      syncStatus = FileRecord.SyncStatus.PENDING,
      lastSynced = null,
      errorMessage = null,
    )
  }
}
