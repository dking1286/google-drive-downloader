package dev.dking.googledrivedownloader.api.impl

/**
 * Base exception class for all Google Drive API-related errors.
 * All specific exceptions inherit from this sealed class for type-safe error handling.
 */
sealed class DriveApiException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when OAuth 2.0 authentication fails or tokens are invalid.
 * This includes user cancellation, network errors during auth, and invalid credentials.
 */
class AuthenticationException(message: String, cause: Throwable? = null) :
  DriveApiException(message, cause)

/**
 * General API exception for non-transient errors that should not be retried.
 * Examples: invalid request parameters, missing resources (when not specifically 404).
 */
class ApiException(message: String, cause: Throwable? = null) :
  DriveApiException(message, cause)

/**
 * Exception for transient errors that may succeed on retry.
 * Includes rate limiting (429), service unavailable (503), and temporary network issues.
 */
class TransientApiException(message: String, cause: Throwable? = null) :
  DriveApiException(message, cause)

/**
 * Exception thrown when a requested file or resource is not found (404).
 * This is a permanent error that should not be retried.
 */
class FileNotFoundException(message: String, cause: Throwable? = null) :
  DriveApiException(message, cause)
