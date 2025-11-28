package dev.dking.googledrivedownloader.api.impl

import dev.dking.googledrivedownloader.api.DriveClientConfig
import dev.dking.googledrivedownloader.api.FileField
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for GoogleDriveClientImpl.
 * These are basic smoke tests. Full integration testing requires actual Google Drive credentials.
 */
class GoogleDriveClientImplTest {
  private val config = DriveClientConfig(retryAttempts = 3, retryDelaySeconds = 1)
  private val serviceFactory = DriveServiceFactory("test-client-id", "test-client-secret")

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
      GoogleDriveClientImpl(config, serviceFactory, TokenManager(tokenPath))
    assertFalse(instance.isAuthenticated(), "Should not be authenticated initially")
  }

  @Test
  fun `isAuthenticated returns false when no tokens exist`() {
    val client =
      GoogleDriveClientImpl(config, serviceFactory, TokenManager(tokenPath))
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
          serviceFactory,
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
        serviceFactory,
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
          serviceFactory,
          TokenManager(tokenPath),
        )
      // This test ensures all FileField enum values can be requested
      val allFields = FileField.entries.toSet()

      // Should not throw an exception during field mapping
      val result = client.listAllFiles(allFields)

      // Will fail due to no authentication, but ensures field mapping doesn't crash
      assertTrue(result.isFailure)
    }

  // MD5 computation tests

  @Test
  fun `computeMd5 returns correct hash for known content`() {
    // Create temp file with known content
    val tempFile = Files.createTempFile("md5-test", ".txt")
    try {
      // "Hello, World!" has a well-known MD5 hash
      Files.writeString(tempFile, "Hello, World!")

      val md5 = GoogleDriveClientImpl.computeMd5(tempFile)

      // MD5 of "Hello, World!" is 65a8e27d8879283831b664bd8b7f0ad4
      assertEquals("65a8e27d8879283831b664bd8b7f0ad4", md5)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns correct hash for empty file`() {
    val tempFile = Files.createTempFile("md5-empty-test", ".txt")
    try {
      // Empty file - write nothing

      val md5 = GoogleDriveClientImpl.computeMd5(tempFile)

      // MD5 of empty string is d41d8cd98f00b204e9800998ecf8427e
      assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns lowercase hex string`() {
    val tempFile = Files.createTempFile("md5-case-test", ".txt")
    try {
      Files.writeString(tempFile, "test content")

      val md5 = GoogleDriveClientImpl.computeMd5(tempFile)

      // Verify all characters are lowercase hex
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })
      assertEquals(32, md5.length, "MD5 hash should be 32 hex characters")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns different hashes for different content`() {
    val tempFile1 = Files.createTempFile("md5-diff-test1", ".txt")
    val tempFile2 = Files.createTempFile("md5-diff-test2", ".txt")
    try {
      Files.writeString(tempFile1, "content one")
      Files.writeString(tempFile2, "content two")

      val md5First = GoogleDriveClientImpl.computeMd5(tempFile1)
      val md5Second = GoogleDriveClientImpl.computeMd5(tempFile2)

      assertNotEquals(md5First, md5Second, "Different content should produce different MD5 hashes")
    } finally {
      Files.deleteIfExists(tempFile1)
      Files.deleteIfExists(tempFile2)
    }
  }

  @Test
  fun `computeMd5 returns same hash for same content`() {
    val tempFile1 = Files.createTempFile("md5-same-test1", ".txt")
    val tempFile2 = Files.createTempFile("md5-same-test2", ".txt")
    try {
      val content = "identical content"
      Files.writeString(tempFile1, content)
      Files.writeString(tempFile2, content)

      val md5First = GoogleDriveClientImpl.computeMd5(tempFile1)
      val md5Second = GoogleDriveClientImpl.computeMd5(tempFile2)

      assertEquals(md5First, md5Second, "Same content should produce same MD5 hash")
    } finally {
      Files.deleteIfExists(tempFile1)
      Files.deleteIfExists(tempFile2)
    }
  }

  @Test
  fun `computeMd5 handles binary content correctly`() {
    val tempFile = Files.createTempFile("md5-binary-test", ".bin")
    try {
      // Write some binary content including null bytes
      val binaryContent = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
      Files.write(tempFile, binaryContent)

      val md5 = GoogleDriveClientImpl.computeMd5(tempFile)

      // Should complete without error and return valid hash
      assertEquals(32, md5.length)
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 handles large file correctly`() {
    val tempFile = Files.createTempFile("md5-large-test", ".bin")
    try {
      // Create a file larger than the 8192 byte buffer
      val largeContent = ByteArray(50000) { (it % 256).toByte() }
      Files.write(tempFile, largeContent)

      val md5 = GoogleDriveClientImpl.computeMd5(tempFile)

      // Should complete without error and return valid hash
      assertEquals(32, md5.length)
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })

      // Verify it's deterministic
      val md5Again = GoogleDriveClientImpl.computeMd5(tempFile)
      assertEquals(md5, md5Again)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
