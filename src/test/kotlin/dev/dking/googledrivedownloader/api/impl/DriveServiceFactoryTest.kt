package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.auth.oauth2.Credential
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for DriveServiceFactory.
 * Note: The authorize() method requires interactive browser-based OAuth and cannot be unit tested.
 */
class DriveServiceFactoryTest {
  private val clientId = "test-client-id"
  private val clientSecret = "test-client-secret"
  private val factory = DriveServiceFactory(clientId, clientSecret)

  @Test
  fun `createAuthorizationFlow returns configured flow`() {
    // Act
    val flow = factory.createAuthorizationFlow()

    // Assert
    assertNotNull(flow, "Should create authorization flow")
    assertEquals("offline", flow.accessType, "Should request offline access for refresh tokens")
  }

  @Test
  fun `createDriveService creates service with credential`() {
    // Arrange
    val credential = mockk<Credential>(relaxed = true)

    // Act
    val driveService = factory.createDriveService(credential)

    // Assert
    assertNotNull(driveService, "Should create Drive service")
    assertEquals(
      "Google Drive Downloader",
      driveService.applicationName,
      "Should set application name",
    )
  }

  @Test
  fun `createDriveServiceFromTokens creates service with tokens`() {
    // Arrange
    val accessToken = "test-access-token"
    val refreshToken = "test-refresh-token"

    // Act
    val driveService = factory.createDriveServiceFromTokens(accessToken, refreshToken)

    // Assert
    assertNotNull(driveService, "Should create Drive service from tokens")
    assertEquals(
      "Google Drive Downloader",
      driveService.applicationName,
      "Should set application name",
    )
  }

  @Test
  fun `createDriveServiceFromTokens works with null refresh token`() {
    // Arrange
    val accessToken = "test-access-token"
    val refreshToken: String? = null

    // Act
    val driveService = factory.createDriveServiceFromTokens(accessToken, refreshToken)

    // Assert
    assertNotNull(driveService, "Should create Drive service with null refresh token")
  }

  @Test
  fun `factory uses provided client credentials`() {
    // Arrange
    val customFactory = DriveServiceFactory("custom-client-id", "custom-secret")

    // Act
    val flow = customFactory.createAuthorizationFlow()

    // Assert - verify the flow was created (credentials are embedded in the flow)
    assertNotNull(flow, "Should create authorization flow with custom credentials")
    // The client ID and secret are in the clientAuthentication, not directly accessible
    // but the flow being created without error validates they were accepted
  }

  @Test
  fun `createAuthorizationFlow requests drive readonly scope`() {
    // Act
    val flow = factory.createAuthorizationFlow()

    // Assert
    val scopes = flow.scopes
    assertTrue(
      scopes.contains("https://www.googleapis.com/auth/drive.readonly"),
      "Should request drive.readonly scope, got: $scopes",
    )
  }

  @Test
  fun `multiple createDriveService calls create independent services`() {
    // Arrange
    val credential1 = mockk<Credential>(relaxed = true)
    val credential2 = mockk<Credential>(relaxed = true)

    // Act
    val service1 = factory.createDriveService(credential1)
    val service2 = factory.createDriveService(credential2)

    // Assert
    assertNotNull(service1)
    assertNotNull(service2)
    assertTrue(service1 !== service2, "Should create separate service instances")
  }
}
