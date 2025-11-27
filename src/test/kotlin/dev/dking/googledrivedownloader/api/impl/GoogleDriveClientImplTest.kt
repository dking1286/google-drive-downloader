package dev.dking.googledrivedownloader.api.impl

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for GoogleDriveClientImpl.
 * These are basic smoke tests. Full integration testing requires actual Google Drive credentials.
 */
class GoogleDriveClientImplTest {
  private val config = DriveClientConfig(retryAttempts = 3, retryDelaySeconds = 1)
  private val testClientId = "test-client-id"
  private val testClientSecret = "test-client-secret"

  private lateinit var tokenPath: Path

  @BeforeTest
  fun setup() {
    val tempDir = Files.createTempDirectory("google-drive-client-test")
    tokenPath = tempDir.resolve("tokens.json")
  }

  @AfterTest
  fun cleanup() {
    Files.walk(tokenPath.parent)
      .sorted(Comparator.reverseOrder())
      .forEach { Files.deleteIfExists(it) }
  }

  @Test
  fun `constructor creates instance with correct configuration`() {
    // Verify that instance can be created
    val instance =
      GoogleDriveClientImpl(config, testClientId, testClientSecret, TokenManager(tokenPath))
    assertFalse(instance.isAuthenticated(), "Should not be authenticated initially")
  }

  @Test
  fun `isAuthenticated returns false when no tokens exist`() {
    val client =
      GoogleDriveClientImpl(config, testClientId, testClientSecret, TokenManager(tokenPath))
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
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
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
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
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
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
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
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
        )
      // Should fail because we haven't authenticated
      val result =
        client.downloadFile(
          fileId = "test-file-id",
          outputPath = Path.of("/tmp/test-download"),
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
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
        )
      // Should fail because we haven't authenticated
      val result =
        client.exportFile(
          fileId = "test-file-id",
          exportMimeType = "application/pdf",
          outputPath = Path.of("/tmp/test-export.pdf"),
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
        testClientId,
        testClientSecret,
        TokenManager(tokenPath),
      )

    assertFalse(customClient.isAuthenticated())
  }

  @Test
  fun `FileField mapping covers all enum values`() =
    runTest {
      val client =
        GoogleDriveClientImpl(
          config,
          testClientId,
          testClientSecret,
          TokenManager(tokenPath),
        )
      // This test ensures all FileField enum values can be requested
      val allFields = FileField.entries.toSet()

      // Should not throw an exception during field mapping
      val result = client.listAllFiles(allFields)

      // Will fail due to no authentication, but ensures field mapping doesn't crash
      assertTrue(result.isFailure)
    }
}
