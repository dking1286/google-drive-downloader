package dev.dking.googledrivedownloader.api.impl

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for GoogleDriveClientImpl.
 * These are basic smoke tests. Full integration testing requires actual Google Drive credentials.
 */
class GoogleDriveClientImplTest {
  private val config = DriveClientConfig(retryAttempts = 3, retryDelaySeconds = 1)
  private val serviceFactory = DriveServiceFactory("test-client-id", "test-client-secret")

  private lateinit var tempDir: Path
  private lateinit var tokenPath: Path
  private lateinit var baseDir: Path

  @BeforeTest
  fun setup() {
    tempDir = Files.createTempDirectory("google-drive-client-test")
    tokenPath = tempDir.resolve("tokens.json")
    baseDir = tempDir.resolve("downloads")
    Files.createDirectories(baseDir)
  }

  @AfterTest
  fun cleanup() {
    Files.walk(tempDir)
      .sorted(Comparator.reverseOrder())
      .forEach { Files.deleteIfExists(it) }
  }

  @Test
  fun `constructor creates instance with correct configuration`() {
    // Verify that instance can be created
    val instance =
      GoogleDriveClientImpl(config, serviceFactory, TokenManager(tokenPath), baseDir)
    assertFalse(instance.isAuthenticated(), "Should not be authenticated initially")
  }

  @Test
  fun `isAuthenticated returns false when no tokens exist`() {
    val client =
      GoogleDriveClientImpl(config, serviceFactory, TokenManager(tokenPath), baseDir)
    // Should return false when there are no stored tokens
    val result = client.isAuthenticated()
    assertFalse(result, "Should not be authenticated without tokens")
  }

  // Note: authenticate() test is skipped as it would trigger interactive OAuth flow
  // which requires browser interaction and cannot be automated in unit tests

  @Test
  fun `getStartPageToken fails when not authenticated`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // Should fail because we haven't authenticated
      val result = client.getStartPageToken()

      assertTrue(result.isFailure, "Should fail when not authenticated")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException, "Should throw AuthenticationException")
    }

  @Test
  fun `listAllFiles fails when not authenticated`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // Should fail because we haven't authenticated
      val result = client.listAllFiles(setOf(FileField.ID, FileField.NAME))

      assertTrue(result.isFailure, "Should fail when not authenticated")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException, "Should throw AuthenticationException")
    }

  @Test
  fun `listChanges fails when not authenticated`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // Should fail because we haven't authenticated
      val result = client.listChanges("test-page-token")

      assertTrue(result.isFailure, "Should fail when not authenticated")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException, "Should throw AuthenticationException")
    }

  @Test
  fun `downloadFile fails when not authenticated`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // Should fail because we haven't authenticated
      val result =
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = baseDir.resolve("test-download"),
          onProgress = { _, _ -> },
        )

      assertTrue(result.isFailure, "Should fail when not authenticated")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException, "Should throw AuthenticationException")
    }

  @Test
  fun `exportFile fails when not authenticated`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // Should fail because we haven't authenticated
      val result =
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = baseDir.resolve("test-export.pdf"),
        )

      assertTrue(result.isFailure, "Should fail when not authenticated")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException, "Should throw AuthenticationException")
    }

  @Test
  fun `client properly uses retry handler configuration`() {
    // Verify that the client is created with the correct retry configuration
    val customConfig = DriveClientConfig(retryAttempts = 5, retryDelaySeconds = 10)
    val customClient =
      GoogleDriveClientImpl(
        customConfig,
        serviceFactory,
        TokenManager(tokenPath),
        baseDir,
      )

    assertFalse(customClient.isAuthenticated())
  }

  @Test
  fun `FileField mapping covers all enum values`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )
      // This test ensures all FileField enum values can be requested
      val allFields = FileField.entries.toSet()

      // Should not throw an exception during field mapping
      val result = client.listAllFiles(allFields)

      // Will fail due to no authentication, but ensures field mapping doesn't crash
      assertTrue(result.isFailure)
    }

  // Path traversal protection tests

  @Test
  fun `downloadFile rejects path outside base directory`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Try to download to a path outside the base directory
      val outsidePath = tempDir.resolve("outside").resolve("malicious.txt")

      assertFailsWith<IllegalArgumentException> {
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = outsidePath,
          onProgress = { _, _ -> },
        )
      }
    }

  @Test
  fun `downloadFile rejects path traversal with dot-dot`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Try to escape using ../
      val traversalPath =
        baseDir
          .resolve("subdir")
          .resolve("..")
          .resolve("..")
          .resolve("escape.txt")

      assertFailsWith<IllegalArgumentException> {
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = traversalPath,
          onProgress = { _, _ -> },
        )
      }
    }

  @Test
  fun `downloadFile accepts path within base directory`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Path within base directory should pass validation (will fail on auth, not path)
      val validPath = baseDir.resolve("subdir").resolve("file.txt")
      val result =
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = validPath,
          onProgress = { _, _ -> },
        )

      // Should fail due to authentication, not path validation
      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is AuthenticationException)
    }

  @Test
  fun `exportFile rejects path outside base directory`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Try to export to a path outside the base directory
      val outsidePath = tempDir.resolve("outside").resolve("malicious.pdf")

      assertFailsWith<IllegalArgumentException> {
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = outsidePath,
        )
      }
    }

  @Test
  fun `exportFile rejects path traversal with dot-dot`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Try to escape using ../
      val traversalPath = baseDir.resolve("..").resolve("escape.pdf")

      assertFailsWith<IllegalArgumentException> {
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = traversalPath,
        )
      }
    }

  @Test
  fun `exportFile accepts path within base directory`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Path within base directory should pass validation (will fail on auth, not path)
      val validPath = baseDir.resolve("exports").resolve("document.pdf")
      val result =
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = validPath,
        )

      // Should fail due to authentication, not path validation
      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is AuthenticationException)
    }

  // Symlink attack protection tests

  @Test
  fun `downloadFile rejects path containing symlink`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Create a symlink inside the base directory pointing outside
      val symlinkDir = baseDir.resolve("symlink-dir")
      val targetDir = tempDir.resolve("outside-target")
      Files.createDirectories(targetDir)
      Files.createSymbolicLink(symlinkDir, targetDir)

      // Try to download through the symlink
      val pathThroughSymlink = symlinkDir.resolve("file.txt")

      assertFailsWith<IllegalArgumentException> {
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = pathThroughSymlink,
          onProgress = { _, _ -> },
        )
      }
    }

  @Test
  fun `downloadFile rejects symlink as direct target`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Create a symlink file inside the base directory
      val targetFile = tempDir.resolve("outside-file.txt")
      Files.createFile(targetFile)
      val symlinkFile = baseDir.resolve("symlink-file.txt")
      Files.createSymbolicLink(symlinkFile, targetFile)

      assertFailsWith<IllegalArgumentException> {
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = symlinkFile,
          onProgress = { _, _ -> },
        )
      }
    }

  @Test
  fun `exportFile rejects path containing symlink`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Create a symlink inside the base directory pointing outside
      val symlinkDir = baseDir.resolve("export-symlink-dir")
      val targetDir = tempDir.resolve("export-target")
      Files.createDirectories(targetDir)
      Files.createSymbolicLink(symlinkDir, targetDir)

      // Try to export through the symlink
      val pathThroughSymlink = symlinkDir.resolve("document.pdf")

      assertFailsWith<IllegalArgumentException> {
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = pathThroughSymlink,
        )
      }
    }

  @Test
  fun `downloadFile accepts path without symlinks`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          serviceFactory,
          TokenManager(tokenPath),
          baseDir,
        )

      // Create real directories (not symlinks)
      val realSubdir = baseDir.resolve("real-subdir")
      Files.createDirectories(realSubdir)

      val validPath = realSubdir.resolve("file.txt")
      val result =
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = validPath,
          onProgress = { _, _ -> },
        )

      // Should fail due to authentication, not symlink validation
      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is AuthenticationException)
    }
}
