package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.auth.oauth2.Credential
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Manages OAuth 2.0 token persistence and validation.
 * Stores tokens in ~/.google-drive-downloader/tokens.json with secure file permissions.
 */
class TokenManager(
    private val tokenPath: Path = defaultTokenPath()
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    companion object {
        private const val EXPIRY_BUFFER_MINUTES = 5L

        fun defaultTokenPath(): Path {
            val home = System.getProperty("user.home")
            val configDir = Paths.get(home, ".google-drive-downloader")
            return configDir.resolve("tokens.json")
        }
    }

    /**
     * Data class representing stored OAuth tokens.
     */
    @Serializable
    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String?,
        val tokenType: String,
        val expiresAt: String, // ISO 8601 timestamp
        val scope: String
    )

    /**
     * Save OAuth tokens from a Google Credential object to persistent storage.
     * Creates parent directories if needed and sets secure file permissions (600).
     *
     * @param credential The Google API credential containing tokens
     */
    fun saveTokens(credential: Credential) {
        try {
            // Create parent directories if they don't exist
            Files.createDirectories(tokenPath.parent)

            // Calculate expiration time
            val expiresInSeconds = credential.expiresInSeconds ?: 3600L
            val expiresAt = Instant.now().plusSeconds(expiresInSeconds)

            // Create stored tokens object
            val tokens = StoredTokens(
                accessToken = credential.accessToken ?: throw IllegalStateException("No access token"),
                refreshToken = credential.refreshToken,
                tokenType = "Bearer",
                expiresAt = expiresAt.toString(),
                scope = "https://www.googleapis.com/auth/drive.readonly"
            )

            // Serialize and write to file
            val jsonString = json.encodeToString(tokens)
            Files.writeString(tokenPath, jsonString)

            // Set secure file permissions (owner read/write only)
            setFilePermissions(tokenPath)

            logger.info { "Tokens saved to $tokenPath" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save tokens" }
            throw ApiException("Failed to save tokens: ${e.message}", e)
        }
    }

    /**
     * Load tokens from persistent storage.
     *
     * @return StoredTokens if file exists and is valid, null otherwise
     */
    fun loadTokens(): StoredTokens? {
        return try {
            if (!Files.exists(tokenPath)) {
                logger.debug { "No token file found at $tokenPath" }
                return null
            }

            val jsonString = Files.readString(tokenPath)
            val tokens = json.decodeFromString<StoredTokens>(jsonString)
            logger.debug { "Tokens loaded successfully from $tokenPath" }
            tokens
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load tokens from $tokenPath" }
            null
        }
    }

    /**
     * Check if the given tokens are valid (not expired).
     * Uses a buffer of 5 minutes to preemptively refresh tokens.
     *
     * @param tokens The tokens to validate
     * @return true if tokens are valid and not expired, false otherwise
     */
    fun isTokenValid(tokens: StoredTokens): Boolean {
        return try {
            val expiresAt = Instant.parse(tokens.expiresAt)
            val now = Instant.now()
            val bufferTime = now.plusSeconds(EXPIRY_BUFFER_MINUTES * 60)

            val isValid = expiresAt.isAfter(bufferTime)

            if (isValid) {
                logger.debug { "Tokens are valid until $expiresAt" }
            } else {
                logger.debug { "Tokens expired or expiring soon (expires at $expiresAt)" }
            }

            isValid
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse token expiration time" }
            false
        }
    }

    /**
     * Clear stored tokens by deleting the token file.
     */
    fun clearTokens() {
        try {
            if (Files.exists(tokenPath)) {
                Files.delete(tokenPath)
                logger.info { "Tokens cleared from $tokenPath" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to clear tokens" }
        }
    }

    /**
     * Set secure file permissions (600 - owner read/write only).
     * Gracefully handles non-POSIX systems (e.g., Windows).
     *
     * @param path The file path to set permissions on
     */
    private fun setFilePermissions(path: Path) {
        try {
            val permissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
            Files.setPosixFilePermissions(path, permissions)
            logger.debug { "Set file permissions to 600 on $path" }
        } catch (e: UnsupportedOperationException) {
            // Windows or other non-POSIX systems
            logger.warn { "Unable to set POSIX permissions on this platform" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to set file permissions" }
        }
    }
}
