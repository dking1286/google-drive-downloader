package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import dev.dking.googledrivedownloader.api.DriveClientConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.pow
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Handles retry logic with exponential backoff for API operations.
 * Distinguishes between transient errors (retryable) and permanent errors (fail immediately).
 */
class RetryHandler(
    private val config: DriveClientConfig
) {
    /**
     * Execute an operation with retry logic and exponential backoff.
     * Retries transient errors (429, 503, 500, timeouts) but fails immediately on permanent errors.
     *
     * @param T The return type of the operation
     * @param operation The suspend function to execute
     * @return Result containing the operation result or final error
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> T
    ): Result<T> {
        var currentDelay = config.retryDelaySeconds * 1000L // Convert to milliseconds
        var lastException: Exception? = null

        repeat(config.retryAttempts) { attempt ->
            try {
                logger.debug { "Executing operation (attempt ${attempt + 1}/${config.retryAttempts})" }
                val result = operation()
                return Result.success(result)
            } catch (e: Exception) {
                lastException = e

                // Check if this is a transient error that should be retried
                if (!isTransientError(e)) {
                    // Permanent error - fail immediately
                    logger.error(e) { "Permanent error encountered, not retrying" }
                    return Result.failure(convertToApiException(e))
                }

                // Transient error - retry if we have attempts left
                if (attempt < config.retryAttempts - 1) {
                    // Calculate backoff with jitter
                    val jitter = Random.nextDouble(0.75, 1.25) // ±25% jitter
                    val exponentialDelay = currentDelay * (2.0.pow(attempt))
                    val delayMs = (exponentialDelay * jitter).toLong()

                    logger.warn { "Transient error on attempt ${attempt + 1}, retrying after ${delayMs}ms: ${e.message}" }

                    delay(delayMs)
                } else {
                    logger.error(e) { "Operation failed after ${config.retryAttempts} attempts" }
                }
            }
        }

        // All attempts exhausted
        return Result.failure(lastException ?: ApiException("Operation failed after ${config.retryAttempts} attempts"))
    }

    /**
     * Determine if an error is transient (retryable) or permanent.
     * Transient errors: 429 (rate limit), 503 (service unavailable), 500 (server error), network timeouts
     * Permanent errors: 401 (unauthorized), 403 (forbidden), 404 (not found), 400 (bad request)
     *
     * @param error The exception to classify
     * @return true if the error is transient and should be retried, false otherwise
     */
    private fun isTransientError(error: Exception): Boolean {
        return when (error) {
            // Custom exceptions first (most specific)
            is TransientApiException -> {
                logger.debug { "Classified as transient: TransientApiException" }
                true
            }
            is AuthenticationException -> {
                logger.debug { "Classified as permanent: AuthenticationException" }
                false
            }
            is FileNotFoundException -> {
                logger.debug { "Classified as permanent: FileNotFoundException" }
                false
            }
            is ApiException -> {
                logger.debug { "Classified as permanent: ApiException" }
                false
            }
            // Google API exceptions (before IOException since GoogleJsonResponseException extends IOException)
            is GoogleJsonResponseException -> {
                // Check HTTP status code
                val statusCode = error.statusCode
                logger.debug { "GoogleJsonResponseException status code: $statusCode, message: ${error.message}" }
                val isTransient = when (statusCode) {
                    429 -> true // Rate limit
                    500 -> true // Internal server error
                    502 -> true // Bad gateway
                    503 -> true // Service unavailable
                    504 -> true // Gateway timeout
                    401 -> false // Unauthorized
                    403 -> false // Forbidden
                    404 -> false // Not found
                    400 -> false // Bad request
                    else -> {
                        logger.warn { "Unknown HTTP status code $statusCode, treating as permanent error" }
                        false // Default to permanent for unknown codes
                    }
                }
                logger.debug { "Classified GoogleJsonResponseException with status $statusCode as ${if (isTransient) "transient" else "permanent"}" }
                isTransient
            }
            // Network-level errors (check after more specific exceptions)
            is SocketTimeoutException -> {
                logger.debug { "Classified as transient: SocketTimeoutException" }
                true
            }
            is IOException -> {
                // Generic IO exceptions might be transient (network issues)
                logger.debug { "Classified as transient: IOException" }
                true
            }
            else -> {
                // Unknown exceptions - default to permanent (don't retry)
                logger.debug { "Classified unknown exception ${error.javaClass.simpleName} as permanent (default)" }
                false
            }
        }
    }

    /**
     * Convert a generic exception to an appropriate API exception type.
     *
     * @param error The exception to convert
     * @return An appropriate DriveApiException subtype
     */
    private fun convertToApiException(error: Exception): DriveApiException {
        return when (error) {
            is DriveApiException -> error // Already an API exception
            is GoogleJsonResponseException -> {
                when (error.statusCode) {
                    401, 403 -> AuthenticationException("Authentication failed: ${error.message}", error)
                    404 -> FileNotFoundException("Resource not found: ${error.message}", error)
                    429, 500, 502, 503, 504 -> TransientApiException("Transient API error: ${error.message}", error)
                    else -> ApiException("API error: ${error.message}", error)
                }
            }
            is SocketTimeoutException -> TransientApiException("Request timeout: ${error.message}", error)
            is IOException -> TransientApiException("Network error: ${error.message}", error)
            else -> ApiException("Unexpected error: ${error.message}", error)
        }
    }
}
