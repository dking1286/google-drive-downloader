package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.auth.oauth2.Credential
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenManagerTest {
  private lateinit var tempDir: Path
  private lateinit var tokenPath: Path
  private lateinit var tokenManager: TokenManager

  @BeforeTest
  fun setup() {
    // Create a temporary directory for test tokens
    tempDir = Files.createTempDirectory("token-manager-test")
    tokenPath = tempDir.resolve("tokens.json")
    tokenManager = TokenManager(tokenPath)
  }

  @AfterTest
  fun cleanup() {
    // Clean up temp directory
    Files.walk(tempDir)
      .sorted(Comparator.reverseOrder())
      .forEach { Files.deleteIfExists(it) }
  }

  @Test
  fun `saveTokens creates file and saves tokens`() {
    // Arrange
    val credential = mockk<Credential>()
    every { credential.accessToken } returns "test-access-token"
    every { credential.refreshToken } returns "test-refresh-token"
    every { credential.expiresInSeconds } returns 3600L

    // Act
    tokenManager.saveTokens(credential)

    // Assert
    assertTrue(Files.exists(tokenPath), "Token file should exist")
    val content = Files.readString(tokenPath)
    assertTrue(content.contains("test-access-token"), "Should contain access token")
    assertTrue(content.contains("test-refresh-token"), "Should contain refresh token")
  }

  @Test
  fun `saveTokens creates parent directories`() {
    // Arrange
    val nestedPath = tempDir.resolve("nested/dir/tokens.json")
    val nestedTokenManager = TokenManager(nestedPath)
    val credential = mockk<Credential>()
    every { credential.accessToken } returns "test-access-token"
    every { credential.refreshToken } returns "test-refresh-token"
    every { credential.expiresInSeconds } returns 3600L

    // Act
    nestedTokenManager.saveTokens(credential)

    // Assert
    assertTrue(Files.exists(nestedPath), "Token file should exist in nested directory")
  }

  @Test
  fun `saveTokens throws exception when credential has no access token`() {
    // Arrange
    val credential = mockk<Credential>()
    every { credential.accessToken } returns null
    every { credential.refreshToken } returns "test-refresh-token"
    every { credential.expiresInSeconds } returns 3600L

    // Act & Assert
    val exception =
      assertFails {
        tokenManager.saveTokens(credential)
      }
    assertTrue(exception is ApiException)
  }

  @Test
  fun `loadTokens returns null for missing file`() {
    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNull(tokens, "Should return null when file doesn't exist")
  }

  @Test
  fun `loadTokens returns null for invalid JSON`() {
    // Arrange
    Files.writeString(tokenPath, "invalid json content")

    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNull(tokens, "Should return null for invalid JSON")
  }

  @Test
  fun `loadTokens deserializes valid tokens`() {
    // Arrange
    val expiresAt = Instant.now().plusSeconds(3600)
    val jsonContent =
      """
      {
        "accessToken": "test-access-token",
        "refreshToken": "test-refresh-token",
        "tokenType": "Bearer",
        "expiresAt": "$expiresAt",
        "scope": "https://www.googleapis.com/auth/drive.readonly"
      }
      """.trimIndent()
    Files.writeString(tokenPath, jsonContent)

    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNotNull(tokens, "Should load tokens successfully")
    assertEquals("test-access-token", tokens.accessToken)
    assertEquals("test-refresh-token", tokens.refreshToken)
    assertEquals("Bearer", tokens.tokenType)
    assertEquals(expiresAt.toString(), tokens.expiresAt)
  }

  @Test
  fun `isTokenValid returns true for valid tokens`() {
    // Arrange - tokens that expire in 10 minutes
    val expiresAt = Instant.now().plusSeconds(600) // 10 minutes
    val tokens =
      TokenManager.StoredTokens(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        tokenType = "Bearer",
        expiresAt = expiresAt.toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    // Act
    val isValid = tokenManager.isTokenValid(tokens)

    // Assert
    assertTrue(isValid, "Tokens expiring in 10 minutes should be valid")
  }

  @Test
  fun `isTokenValid returns false for expired tokens`() {
    // Arrange - tokens that expired 1 hour ago
    val expiresAt = Instant.now().minusSeconds(3600)
    val tokens =
      TokenManager.StoredTokens(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        tokenType = "Bearer",
        expiresAt = expiresAt.toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    // Act
    val isValid = tokenManager.isTokenValid(tokens)

    // Assert
    assertFalse(isValid, "Expired tokens should be invalid")
  }

  @Test
  fun `isTokenValid returns false for tokens expiring within buffer`() {
    // Arrange - tokens that expire in 2 minutes (within 5-minute buffer)
    val expiresAt = Instant.now().plusSeconds(120)
    val tokens =
      TokenManager.StoredTokens(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        tokenType = "Bearer",
        expiresAt = expiresAt.toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    // Act
    val isValid = tokenManager.isTokenValid(tokens)

    // Assert
    assertFalse(isValid, "Tokens expiring within 5-minute buffer should be invalid")
  }

  @Test
  fun `isTokenValid returns false for malformed expiry timestamp`() {
    // Arrange
    val tokens =
      TokenManager.StoredTokens(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        tokenType = "Bearer",
        expiresAt = "invalid-timestamp",
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    // Act
    val isValid = tokenManager.isTokenValid(tokens)

    // Assert
    assertFalse(isValid, "Should return false for malformed timestamp")
  }

  @Test
  fun `clearTokens deletes token file`() {
    // Arrange - create a token file first
    Files.writeString(tokenPath, "test content")
    assertTrue(Files.exists(tokenPath), "Setup: token file should exist")

    // Act
    tokenManager.clearTokens()

    // Assert
    assertFalse(Files.exists(tokenPath), "Token file should be deleted")
  }

  @Test
  fun `clearTokens handles missing file gracefully`() {
    // Act & Assert - should not throw exception
    tokenManager.clearTokens()
    // If we get here without exception, test passes
    assertTrue(true)
  }

  @Test
  fun `saveTokens and loadTokens round-trip successfully`() {
    // Arrange
    val credential = mockk<Credential>()
    every { credential.accessToken } returns "round-trip-access"
    every { credential.refreshToken } returns "round-trip-refresh"
    every { credential.expiresInSeconds } returns 7200L

    // Act
    tokenManager.saveTokens(credential)
    val loaded = tokenManager.loadTokens()

    // Assert
    assertNotNull(loaded, "Should load saved tokens")
    assertEquals("round-trip-access", loaded.accessToken)
    assertEquals("round-trip-refresh", loaded.refreshToken)
    assertEquals("Bearer", loaded.tokenType)
    assertEquals("https://www.googleapis.com/auth/drive.readonly", loaded.scope)
  }

  // Edge case tests

  @Test
  fun `saveTokens handles null refresh token`() {
    // Arrange
    val credential = mockk<Credential>()
    every { credential.accessToken } returns "test-access-token"
    every { credential.refreshToken } returns null
    every { credential.expiresInSeconds } returns 3600L

    // Act
    tokenManager.saveTokens(credential)
    val loaded = tokenManager.loadTokens()

    // Assert
    assertNotNull(loaded, "Should load saved tokens")
    assertEquals("test-access-token", loaded.accessToken)
    assertNull(loaded.refreshToken, "Refresh token should be null")
  }

  @Test
  fun `saveTokens handles null expiresInSeconds with default`() {
    // Arrange
    val credential = mockk<Credential>()
    every { credential.accessToken } returns "test-access-token"
    every { credential.refreshToken } returns "test-refresh-token"
    every { credential.expiresInSeconds } returns null // Default should be 3600L

    // Act
    tokenManager.saveTokens(credential)
    val loaded = tokenManager.loadTokens()

    // Assert
    assertNotNull(loaded, "Should load saved tokens")
    assertEquals("test-access-token", loaded.accessToken)
    // Verify the expiry is approximately 1 hour in the future
    val expiresAt = Instant.parse(loaded.expiresAt)
    val now = Instant.now()
    assertTrue(
      expiresAt.isAfter(now.plusSeconds(3500)),
      "Should expire approximately 1 hour from now",
    )
    assertTrue(
      expiresAt.isBefore(now.plusSeconds(3700)),
      "Should expire approximately 1 hour from now",
    )
  }

  @Test
  fun `loadTokens ignores unknown keys in JSON`() {
    // Arrange - JSON with extra unknown keys
    val expiresAt = Instant.now().plusSeconds(3600)
    val jsonContent =
      """
      {
        "accessToken": "test-access-token",
        "refreshToken": "test-refresh-token",
        "tokenType": "Bearer",
        "expiresAt": "$expiresAt",
        "scope": "https://www.googleapis.com/auth/drive.readonly",
        "unknownField": "should be ignored",
        "anotherUnknownField": 12345
      }
      """.trimIndent()
    Files.writeString(tokenPath, jsonContent)

    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNotNull(tokens, "Should load tokens even with unknown fields")
    assertEquals("test-access-token", tokens.accessToken)
  }

  @Test
  fun `loadTokens returns null for empty file`() {
    // Arrange
    Files.writeString(tokenPath, "")

    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNull(tokens, "Should return null for empty file")
  }

  @Test
  fun `loadTokens returns null for whitespace-only file`() {
    // Arrange
    Files.writeString(tokenPath, "   \n\t  ")

    // Act
    val tokens = tokenManager.loadTokens()

    // Assert
    assertNull(tokens, "Should return null for whitespace-only file")
  }

  @Test
  fun `isTokenValid returns true for tokens just outside buffer`() {
    // Arrange - tokens that expire in 6 minutes (just outside 5-minute buffer)
    val expiresAt = Instant.now().plusSeconds(360) // 6 minutes
    val tokens =
      TokenManager.StoredTokens(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        tokenType = "Bearer",
        expiresAt = expiresAt.toString(),
        scope = "https://www.googleapis.com/auth/drive.readonly",
      )

    // Act
    val isValid = tokenManager.isTokenValid(tokens)

    // Assert
    assertTrue(isValid, "Tokens expiring in 6 minutes (outside buffer) should be valid")
  }

  @Test
  fun `saveTokens overwrites existing token file`() {
    // Arrange - save initial tokens
    val credential1 = mockk<Credential>()
    every { credential1.accessToken } returns "first-access-token"
    every { credential1.refreshToken } returns "first-refresh-token"
    every { credential1.expiresInSeconds } returns 3600L
    tokenManager.saveTokens(credential1)

    // Act - save new tokens
    val credential2 = mockk<Credential>()
    every { credential2.accessToken } returns "second-access-token"
    every { credential2.refreshToken } returns "second-refresh-token"
    every { credential2.expiresInSeconds } returns 7200L
    tokenManager.saveTokens(credential2)
    val loaded = tokenManager.loadTokens()

    // Assert
    assertNotNull(loaded, "Should load saved tokens")
    assertEquals("second-access-token", loaded.accessToken, "Should have new access token")
    assertEquals("second-refresh-token", loaded.refreshToken, "Should have new refresh token")
  }
}
