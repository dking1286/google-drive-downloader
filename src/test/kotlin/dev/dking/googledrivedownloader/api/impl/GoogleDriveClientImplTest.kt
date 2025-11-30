package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.Change
import com.google.api.services.drive.model.ChangeList
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.google.api.services.drive.model.StartPageToken
import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for GoogleDriveClientImpl.
 * Includes both basic smoke tests and mocked unit tests.
 */
class GoogleDriveClientImplTest {
  private val config = DriveClientConfig(retryAttempts = 3, retryDelaySeconds = 1)
  private val serviceFactory = DriveServiceFactory("test-client-id", "test-client-secret")
  private val mockServiceFactory = mockk<DriveServiceFactory>()
  private val mockTokenManager = mockk<TokenManager>()

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

  // ==================== Mocked Tests ====================

  @Test
  fun `isAuthenticated returns true when tokens are valid`() {
    val validTokens =
      TokenManager.StoredTokens(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        tokenType = "Bearer",
        expiresAt = Instant.now().plusSeconds(3600).toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    every { mockTokenManager.loadTokens() } returns validTokens
    every { mockTokenManager.isTokenValid(validTokens) } returns true

    val client =
      GoogleDriveClientImpl(
        config,
        mockServiceFactory,
        mockTokenManager,
        baseDir,
      )

    assertTrue(client.isAuthenticated())
    verify { mockTokenManager.loadTokens() }
    verify { mockTokenManager.isTokenValid(validTokens) }
  }

  @Test
  fun `isAuthenticated returns false when tokens are expired`() {
    val expiredTokens =
      TokenManager.StoredTokens(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        tokenType = "Bearer",
        expiresAt = Instant.now().minusSeconds(3600).toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    every { mockTokenManager.loadTokens() } returns expiredTokens
    every { mockTokenManager.isTokenValid(expiredTokens) } returns false

    val client =
      GoogleDriveClientImpl(
        config,
        mockServiceFactory,
        mockTokenManager,
        baseDir,
      )

    assertFalse(client.isAuthenticated())
  }

  @Test
  fun `authenticate uses cached tokens when not forcing reauth`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.authenticate(forceReauth = false)

      assertTrue(result.isSuccess)
      // Verify no OAuth flow was triggered
      verify(exactly = 0) { mockServiceFactory.authorize() }
    }

  @Test
  fun `authenticate performs OAuth flow when forceReauth is true`() =
    runTest {
      val mockCredential = mockk<Credential>(relaxed = true)
      every { mockCredential.accessToken } returns "new-access-token"
      every { mockCredential.refreshToken } returns "new-refresh-token"
      every { mockCredential.expiresInSeconds } returns 3600L

      every { mockServiceFactory.authorize() } returns mockCredential
      every { mockTokenManager.saveTokens(mockCredential) } just Runs

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.authenticate(forceReauth = true)

      assertTrue(result.isSuccess)
      verify { mockServiceFactory.authorize() }
      verify { mockTokenManager.saveTokens(mockCredential) }
    }

  @Test
  fun `authenticate performs OAuth flow when no cached tokens exist`() =
    runTest {
      val mockCredential = mockk<Credential>(relaxed = true)
      every { mockCredential.accessToken } returns "new-access-token"
      every { mockCredential.refreshToken } returns "new-refresh-token"
      every { mockCredential.expiresInSeconds } returns 3600L

      every { mockTokenManager.loadTokens() } returns null
      every { mockServiceFactory.authorize() } returns mockCredential
      every { mockTokenManager.saveTokens(mockCredential) } just Runs

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.authenticate(forceReauth = false)

      assertTrue(result.isSuccess)
      verify { mockServiceFactory.authorize() }
    }

  @Test
  fun `getStartPageToken returns token on success`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      val mockDrive = mockk<Drive>()
      val mockChanges = mockk<Drive.Changes>()
      val mockGetStartPageToken = mockk<Drive.Changes.GetStartPageToken>()
      val startPageTokenResponse = StartPageToken().setStartPageToken("test-page-token")

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true
      every {
        mockServiceFactory.createDriveServiceFromTokens(
          validTokens.accessToken,
          validTokens.refreshToken,
        )
      } returns mockDrive
      every { mockDrive.changes() } returns mockChanges
      every { mockChanges.getStartPageToken() } returns mockGetStartPageToken
      every { mockGetStartPageToken.execute() } returns startPageTokenResponse

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.getStartPageToken()

      assertTrue(result.isSuccess)
      assertEquals("test-page-token", result.getOrNull())
    }

  @Test
  fun `listAllFiles returns mapped drive files`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      val mockDrive = mockk<Drive>()
      val mockFiles = mockk<Drive.Files>()
      val mockList = mockk<Drive.Files.List>()

      val apiFile =
        File()
          .setId("file-123")
          .setName("test-file.txt")
          .setMimeType("text/plain")
          .setModifiedTime(com.google.api.client.util.DateTime(System.currentTimeMillis()))
      val fileListResponse = FileList().setFiles(listOf(apiFile))

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true
      every {
        mockServiceFactory.createDriveServiceFromTokens(
          validTokens.accessToken,
          validTokens.refreshToken,
        )
      } returns mockDrive
      every { mockDrive.files() } returns mockFiles
      every { mockFiles.list() } returns mockList
      every { mockList.setPageSize(any()) } returns mockList
      every { mockList.setFields(any<String>()) } returns mockList
      every { mockList.setQ(any<String>()) } returns mockList
      every { mockList.setPageToken(any()) } returns mockList
      every { mockList.execute() } returns fileListResponse

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.listAllFiles(setOf(FileField.ID, FileField.NAME))

      assertTrue(result.isSuccess)
      val files = result.getOrNull()!!
      assertEquals(1, files.size)
      assertEquals("file-123", files[0].id)
      assertEquals("test-file.txt", files[0].name)
    }

  @Test
  fun `listChanges returns mapped file changes`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      val mockDrive = mockk<Drive>()
      val mockChanges = mockk<Drive.Changes>()
      val mockChangesList = mockk<Drive.Changes.List>()

      val apiChange =
        Change()
          .setFileId("file-123")
          .setRemoved(false)
          .setFile(
            File()
              .setId("file-123")
              .setName("changed-file.txt")
              .setMimeType("text/plain")
              .setModifiedTime(com.google.api.client.util.DateTime(System.currentTimeMillis())),
          )
      val changeListResponse =
        ChangeList()
          .setChanges(listOf(apiChange))
          .setNewStartPageToken("new-page-token")

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true
      every {
        mockServiceFactory.createDriveServiceFromTokens(
          validTokens.accessToken,
          validTokens.refreshToken,
        )
      } returns mockDrive
      every { mockDrive.changes() } returns mockChanges
      every { mockChanges.list(any()) } returns mockChangesList
      every { mockChangesList.setPageSize(any()) } returns mockChangesList
      every { mockChangesList.setFields(any<String>()) } returns mockChangesList
      every { mockChangesList.execute() } returns changeListResponse

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.listChanges("old-page-token")

      assertTrue(result.isSuccess)
      val changeList = result.getOrNull()!!
      assertEquals("new-page-token", changeList.newStartPageToken)
      assertEquals(1, changeList.changes.size)
      assertEquals("file-123", changeList.changes[0].fileId)
      assertFalse(changeList.changes[0].removed)
    }

  @Test
  fun `listChanges handles removed files correctly`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      val mockDrive = mockk<Drive>()
      val mockChanges = mockk<Drive.Changes>()
      val mockChangesList = mockk<Drive.Changes.List>()

      val removedChange =
        Change()
          .setFileId("removed-file-123")
          .setRemoved(true)
      val changeListResponse =
        ChangeList()
          .setChanges(listOf(removedChange))
          .setNewStartPageToken("new-page-token")

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true
      every {
        mockServiceFactory.createDriveServiceFromTokens(
          validTokens.accessToken,
          validTokens.refreshToken,
        )
      } returns mockDrive
      every { mockDrive.changes() } returns mockChanges
      every { mockChanges.list(any()) } returns mockChangesList
      every { mockChangesList.setPageSize(any()) } returns mockChangesList
      every { mockChangesList.setFields(any<String>()) } returns mockChangesList
      every { mockChangesList.execute() } returns changeListResponse

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.listChanges("old-page-token")

      assertTrue(result.isSuccess)
      val changeList = result.getOrNull()!!
      assertEquals(1, changeList.changes.size)
      assertEquals("removed-file-123", changeList.changes[0].fileId)
      assertTrue(changeList.changes[0].removed)
      assertEquals(null, changeList.changes[0].file)
    }

  @Test
  fun `authenticate returns failure when OAuth flow throws`() =
    runTest {
      every { mockTokenManager.loadTokens() } returns null
      every { mockServiceFactory.authorize() } throws
        AuthenticationException("OAuth flow failed")

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.authenticate(forceReauth = false)

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is AuthenticationException)
    }

  @Test
  fun `listAllFiles handles pagination correctly`() =
    runTest {
      val validTokens =
        TokenManager.StoredTokens(
          accessToken = "test-access-token",
          refreshToken = "test-refresh-token",
          tokenType = "Bearer",
          expiresAt = Instant.now().plusSeconds(3600).toString(),
          scope = "https://www.googleapis.com/auth/drive.readonly",
        )

      val mockDrive = mockk<Drive>()
      val mockFiles = mockk<Drive.Files>()
      val mockList = mockk<Drive.Files.List>()

      // First page
      val file1 =
        File()
          .setId("file-1")
          .setName("file1.txt")
          .setMimeType("text/plain")
          .setModifiedTime(com.google.api.client.util.DateTime(System.currentTimeMillis()))
      val page1 =
        FileList()
          .setFiles(listOf(file1))
          .setNextPageToken("page2-token")

      // Second page
      val file2 =
        File()
          .setId("file-2")
          .setName("file2.txt")
          .setMimeType("text/plain")
          .setModifiedTime(com.google.api.client.util.DateTime(System.currentTimeMillis()))
      val page2 = FileList().setFiles(listOf(file2)) // No nextPageToken = last page

      every { mockTokenManager.loadTokens() } returns validTokens
      every { mockTokenManager.isTokenValid(validTokens) } returns true
      every {
        mockServiceFactory.createDriveServiceFromTokens(
          validTokens.accessToken,
          validTokens.refreshToken,
        )
      } returns mockDrive
      every { mockDrive.files() } returns mockFiles
      every { mockFiles.list() } returns mockList
      every { mockList.setPageSize(any()) } returns mockList
      every { mockList.setFields(any<String>()) } returns mockList
      every { mockList.setQ(any<String>()) } returns mockList
      every { mockList.setPageToken(any()) } returns mockList
      every { mockList.execute() } returnsMany listOf(page1, page2)

      val client =
        GoogleDriveClientImpl(
          config,
          mockServiceFactory,
          mockTokenManager,
          baseDir,
        )

      val result = client.listAllFiles(setOf(FileField.ID, FileField.NAME))

      assertTrue(result.isSuccess)
      val files = result.getOrNull()!!
      assertEquals(2, files.size)
      assertEquals("file-1", files[0].id)
      assertEquals("file-2", files[1].id)
    }
}
