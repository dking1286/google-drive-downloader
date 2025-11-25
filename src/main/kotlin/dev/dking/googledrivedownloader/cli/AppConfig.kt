package dev.dking.googledrivedownloader.cli

import java.nio.file.Path

/**
 * Application configuration.
 */
data class AppConfig(
    val downloadDirectory: String,
    val exportFormats: Map<String, String>,
    val maxConcurrentDownloads: Int,
    val retryAttempts: Int,
    val retryDelaySeconds: Int,
    val logLevel: String
) {
    companion object {
        /**
         * Load configuration from file.
         * @param configPath Path to config.json
         * @return Result containing AppConfig or error
         */
        fun load(configPath: Path): Result<AppConfig> {
            // Placeholder - will be implemented later
            return Result.failure(NotImplementedError("Configuration loading not yet implemented"))
        }
    }
}
