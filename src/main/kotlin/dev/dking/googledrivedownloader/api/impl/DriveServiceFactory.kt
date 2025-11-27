package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.StringReader

private val logger = KotlinLogging.logger {}

/**
 * Factory for creating authenticated Google Drive service instances.
 * Handles OAuth 2.0 flow setup and credential management.
 */
class DriveServiceFactory(
  private val clientId: String,
  private val clientSecret: String,
) {
  private val jsonFactory = GsonFactory.getDefaultInstance()
  private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

  companion object {
    private const val APPLICATION_NAME = "Google Drive Downloader"
    private const val CALLBACK_PORT = 8085
  }

  /**
   * Create a GoogleAuthorizationCodeFlow for OAuth 2.0 authentication.
   * The flow includes PKCE support and requests offline access for refresh tokens.
   *
   * @return Configured GoogleAuthorizationCodeFlow
   */
  fun createAuthorizationFlow(): GoogleAuthorizationCodeFlow {
    logger.debug { "Creating OAuth 2.0 authorization flow" }

    // Create client secrets from credentials
    val clientSecretsJson =
      """
      {
          "installed": {
              "client_id": "$clientId",
              "client_secret": "$clientSecret",
              "redirect_uris": ["http://localhost:$CALLBACK_PORT"],
              "auth_uri": "https://accounts.google.com/o/oauth2/auth",
              "token_uri": "https://oauth2.googleapis.com/token"
          }
      }
      """.trimIndent()

    val clientSecrets =
      GoogleClientSecrets.load(
        jsonFactory,
        StringReader(clientSecretsJson),
      )

    // Build authorization flow with offline access for refresh tokens
    return GoogleAuthorizationCodeFlow.Builder(
      httpTransport,
      jsonFactory,
      clientSecrets,
      listOf(DriveScopes.DRIVE_READONLY),
    )
      .setAccessType("offline") // Request refresh token
      .build()
  }

  /**
   * Perform OAuth 2.0 authorization flow to get a credential.
   * Opens a local server to receive the OAuth callback and exchanges the code for tokens.
   *
   * @return Authenticated Credential
   * @throws AuthenticationException if authentication fails
   */
  fun authorize(): Credential {
    return try {
      logger.info { "Starting OAuth 2.0 authorization flow" }

      val flow = createAuthorizationFlow()
      val receiver =
        LocalServerReceiver.Builder()
          .setPort(CALLBACK_PORT)
          .build()

      logger.info { "Local server started on port $CALLBACK_PORT for OAuth callback" }
      logger.info { "Please authorize the application in your browser" }

      val credential =
        AuthorizationCodeInstalledApp(flow, receiver)
          .authorize("user")

      logger.info { "Authorization successful" }
      credential
    } catch (e: Exception) {
      logger.error(e) { "OAuth authorization failed" }
      throw AuthenticationException("Failed to authorize with Google Drive: ${e.message}", e)
    }
  }

  /**
   * Create a Drive service instance using the provided credential.
   *
   * @param credential Authenticated Google credential
   * @return Configured Drive service
   */
  fun createDriveService(credential: Credential): Drive {
    logger.debug { "Creating Google Drive service instance" }

    return Drive.Builder(httpTransport, jsonFactory, credential)
      .setApplicationName(APPLICATION_NAME)
      .build()
  }

  /**
   * Create a Drive service from stored tokens (via TokenManager).
   * This is used when valid tokens already exist.
   *
   * @param accessToken The access token
   * @param refreshToken The refresh token (optional)
   * @return Configured Drive service
   */
  fun createDriveServiceFromTokens(
    accessToken: String,
    refreshToken: String?,
  ): Drive {
    logger.debug { "Creating Drive service from stored tokens" }

    val flow = createAuthorizationFlow()

    // Create credential from stored tokens
    val credential =
      Credential.Builder(
        com.google.api.client.auth.oauth2.BearerToken.authorizationHeaderAccessMethod(),
      )
        .setTransport(httpTransport)
        .setJsonFactory(jsonFactory)
        .setTokenServerEncodedUrl(flow.tokenServerEncodedUrl)
        .setClientAuthentication(flow.clientAuthentication)
        .build()
        .apply {
          this.accessToken = accessToken
          this.refreshToken = refreshToken
        }

    return createDriveService(credential)
  }
}
