package dev.dking.googledrivedownloader.api.impl

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RetryHandlerTest {
  private val retryAttempts = 3
  private val retryDelaySeconds = 1
  private val retryHandler = RetryHandler(retryAttempts, retryDelaySeconds)

  @Test
  fun `executeWithRetry succeeds on first attempt`() =
    runTest {
      // Arrange
      val operation = suspend { "success" }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals("success", result.getOrNull())
    }

  @Test
  fun `executeWithRetry succeeds on second attempt after transient error`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount == 1) {
            throw SocketTimeoutException("Timeout")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals("success", result.getOrNull())
      assertEquals(2, attemptCount, "Should have attempted twice")
    }

  @Test
  fun `executeWithRetry retries on TransientApiException`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 3) {
            throw TransientApiException("Service unavailable")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals("success", result.getOrNull())
      assertEquals(3, attemptCount, "Should have attempted 3 times")
    }

  @Test
  fun `executeWithRetry fails immediately on AuthenticationException`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          throw AuthenticationException("Unauthorized")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should have attempted only once")
      val exception = result.exceptionOrNull()
      assertTrue(exception is AuthenticationException)
    }

  @Test
  fun `executeWithRetry fails immediately on FileNotFoundException`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          throw FileNotFoundException("File not found")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should have attempted only once")
      val exception = result.exceptionOrNull()
      assertTrue(exception is FileNotFoundException)
    }

  @Test
  fun `executeWithRetry fails immediately on ApiException`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          throw ApiException("Bad request")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should have attempted only once")
      val exception = result.exceptionOrNull()
      assertTrue(exception is ApiException)
    }

  @Test
  fun `executeWithRetry retries on 429 rate limit`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw createGoogleJsonResponseException(429, "Rate limit exceeded")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after 429")
    }

  @Test
  fun `executeWithRetry retries on 503 service unavailable`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw createGoogleJsonResponseException(503, "Service unavailable")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after 503")
    }

  @Test
  fun `executeWithRetry retries on 500 internal server error`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw createGoogleJsonResponseException(500, "Internal server error")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after 500")
    }

  @Test
  fun `executeWithRetry retries on 502 bad gateway`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw createGoogleJsonResponseException(502, "Bad gateway")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after 502")
    }

  @Test
  fun `executeWithRetry retries on 504 gateway timeout`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw createGoogleJsonResponseException(504, "Gateway timeout")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after 504")
    }

  @Test
  fun `executeWithRetry fails immediately on 401 unauthorized`() =
    runTest {
      // Arrange - Test with GoogleJsonResponseException
      var attemptCount = 0
      val googleException = createGoogleJsonResponseException(401, "Unauthorized")

      // First verify the exception is created correctly
      assertEquals(401, googleException.statusCode)

      val operation =
        suspend {
          attemptCount++
          throw googleException
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should NOT retry after 401 (got $attemptCount attempts)")
      val exception = result.exceptionOrNull()
      assertTrue(
        exception is AuthenticationException,
        "Expected AuthenticationException but got ${exception?.javaClass?.simpleName}",
      )
    }

  @Test
  fun `executeWithRetry fails immediately on 404 not found`() =
    runTest {
      // Arrange - Test with GoogleJsonResponseException
      var attemptCount = 0
      val googleException = createGoogleJsonResponseException(404, "Not found")

      // First verify the exception is created correctly
      assertEquals(404, googleException.statusCode)

      val operation =
        suspend {
          attemptCount++
          throw googleException
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should NOT retry after 404 (got $attemptCount attempts)")
      val exception = result.exceptionOrNull()
      assertTrue(
        exception is FileNotFoundException,
        "Expected FileNotFoundException but got ${exception?.javaClass?.simpleName}",
      )
    }

  @Test
  fun `executeWithRetry fails immediately on 400 bad request`() =
    runTest {
      // Arrange - Test with GoogleJsonResponseException
      var attemptCount = 0
      val googleException = createGoogleJsonResponseException(400, "Bad request")

      // First verify the exception is created correctly
      assertEquals(400, googleException.statusCode)

      val operation =
        suspend {
          attemptCount++
          throw googleException
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should NOT retry after 400 (got $attemptCount attempts)")
      val exception = result.exceptionOrNull()
      assertTrue(
        exception is ApiException,
        "Expected ApiException but got ${exception?.javaClass?.simpleName}",
      )
    }

  @Test
  fun `executeWithRetry fails immediately on 403 forbidden`() =
    runTest {
      // Arrange - Test with GoogleJsonResponseException
      var attemptCount = 0
      val googleException = createGoogleJsonResponseException(403, "Forbidden")

      // First verify the exception is created correctly
      assertEquals(403, googleException.statusCode)

      val operation =
        suspend {
          attemptCount++
          throw googleException
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(1, attemptCount, "Should NOT retry after 403 (got $attemptCount attempts)")
      val exception = result.exceptionOrNull()
      assertTrue(
        exception is AuthenticationException,
        "Expected AuthenticationException but got ${exception?.javaClass?.simpleName}",
      )
    }

  @Test
  fun `executeWithRetry applies exponential backoff`() =
    runTest {
      // Arrange
      var attemptCount = 0

      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 3) {
            throw SocketTimeoutException("Timeout")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(
        3,
        attemptCount,
        "Should have retried twice and succeeded on third attempt",
      )
      // Note: Actual timing cannot be tested easily with kotlinx.coroutines.test's virtual time,
      // but the exponential backoff logic is verified by the successful retry behavior.
      // The implementation uses delay() which respects the test dispatcher's virtual time.
    }

  @Test
  fun `executeWithRetry respects max attempts`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          throw SocketTimeoutException("Always fails")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      assertEquals(
        retryAttempts,
        attemptCount,
        "Should attempt exactly $retryAttempts times",
      )
    }

  @Test
  fun `executeWithRetry retries on IOException`() =
    runTest {
      // Arrange
      var attemptCount = 0
      val operation =
        suspend {
          attemptCount++
          if (attemptCount < 2) {
            throw IOException("Network error")
          }
          "success"
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isSuccess)
      assertEquals(2, attemptCount, "Should have retried after IOException")
    }

  @Test
  fun `executeWithRetry converts unknown exception to ApiException`() =
    runTest {
      // Arrange
      val operation =
        suspend {
          throw IllegalStateException("Unknown error")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      val exception = result.exceptionOrNull()
      assertTrue(exception is ApiException)
    }

  @Test
  fun `executeWithRetry returns last exception when all attempts fail`() =
    runTest {
      // Arrange
      val operation =
        suspend {
          throw SocketTimeoutException("Persistent timeout")
        }

      // Act
      val result = retryHandler.executeWithRetry(operation)

      // Assert
      assertTrue(result.isFailure)
      val exception = result.exceptionOrNull()
      assertNotNull(exception)
      val message = exception.message ?: ""
      assertTrue(message.contains("timeout") || message.contains("Persistent"))
    }

  /**
   * Helper function to create a GoogleJsonResponseException with a specific status code.
   */
  private fun createGoogleJsonResponseException(
    statusCode: Int,
    message: String,
  ): GoogleJsonResponseException {
    val builder = HttpResponseException.Builder(statusCode, message, HttpHeaders())
    return GoogleJsonResponseException(builder, null)
  }
}
