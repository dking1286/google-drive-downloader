package dev.dking.googledrivedownloader.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application configuration loaded from config.json.
 */
@Serializable
data class AppConfig(
  @SerialName("download_directory")
  val downloadDirectory: String,
  @SerialName("export_formats")
  val exportFormats: Map<String, String> = DEFAULT_EXPORT_FORMATS,
  @SerialName("max_concurrent_downloads")
  val maxConcurrentDownloads: Int = 4,
  @SerialName("retry_attempts")
  val retryAttempts: Int = 3,
  @SerialName("retry_delay_seconds")
  val retryDelaySeconds: Int = 5,
  @SerialName("log_level")
  val logLevel: String = "INFO",
  @SerialName("client_id")
  val clientId: String,
  @SerialName("client_secret")
  val clientSecret: String,
) {
  companion object {
    private val DEFAULT_EXPORT_FORMATS =
      mapOf(
        "application/vnd.google-apps.document" to
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.google-apps.spreadsheet" to
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.google-apps.presentation" to
          "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.google-apps.drawing" to "image/png",
      )

    private val json =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }

    /**
     * Load configuration from file.
     * @param configPath Path to config.json
     * @return Result containing AppConfig or error
     */
    fun load(configPath: Path): Result<AppConfig> {
      return try {
        if (!Files.exists(configPath)) {
          return Result.failure(
            ConfigurationException("Configuration file not found: $configPath"),
          )
        }

        val content = Files.readString(configPath)
        val config = json.decodeFromString<AppConfig>(content)
        Result.success(config)
      } catch (e: kotlinx.serialization.SerializationException) {
        Result.failure(
          ConfigurationException("Invalid configuration format: ${e.message}", e),
        )
      } catch (e: Exception) {
        Result.failure(
          ConfigurationException("Failed to load configuration: ${e.message}", e),
        )
      }
    }

    /**
     * Get the default configuration file path.
     * @return Path to ~/.google-drive-downloader/config.json
     */
    fun defaultConfigPath(): Path =
      Path.of(System.getProperty("user.home"))
        .resolve(".google-drive-downloader/config.json")
  }
}

/**
 * Exception thrown when configuration loading or validation fails.
 */
class ConfigurationException(message: String, cause: Throwable? = null) :
  Exception(message, cause)
