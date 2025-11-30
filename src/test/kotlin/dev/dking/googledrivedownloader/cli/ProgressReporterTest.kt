package dev.dking.googledrivedownloader.cli

import dev.dking.googledrivedownloader.sync.SyncEvent
import dev.dking.googledrivedownloader.sync.SyncStatusSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressReporterTest {
  private lateinit var outputStream: ByteArrayOutputStream
  private lateinit var output: PrintStream

  @BeforeEach
  fun setUp() {
    outputStream = ByteArrayOutputStream()
    output = PrintStream(outputStream)
  }

  @AfterEach
  fun tearDown() {
    output.close()
  }

  private fun getOutput(): String = outputStream.toString()

  // TTY mode tests

  @Test
  fun `TTY mode includes ANSI colors for checkmark`() {
    val reporter = ProgressReporter(output = output, isTty = true)

    reporter.report(SyncEvent.FileCompleted("file1", "test.txt"))

    val result = getOutput()
    assertContains(result, "\u001B[32m") // Green color code
    assertContains(result, "✓")
    assertContains(result, "test.txt")
  }

  @Test
  fun `non-TTY mode excludes ANSI colors`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.report(SyncEvent.FileCompleted("file1", "test.txt"))

    val result = getOutput()
    assertFalse(result.contains("\u001B["))
    assertContains(result, "✓")
    assertContains(result, "test.txt")
  }

  @Test
  fun `failed files show red X in TTY mode`() {
    val reporter = ProgressReporter(output = output, isTty = true)

    reporter.report(SyncEvent.FileFailed("file1", "test.txt", "Download failed"))

    val result = getOutput()
    assertContains(result, "\u001B[31m") // Red color code
    assertContains(result, "✗")
    assertContains(result, "test.txt")
    assertContains(result, "Download failed")
  }

  // Quiet mode tests

  @Test
  fun `quiet mode suppresses normal output`() {
    val reporter = ProgressReporter(output = output, isTty = false, quiet = true)

    reporter.report(SyncEvent.Started(1L, Instant.now()))
    reporter.report(SyncEvent.DiscoveringFiles(10))
    reporter.report(SyncEvent.FileQueued("file1", "test.txt", 1000))
    reporter.report(SyncEvent.FileCompleted("file1", "test.txt"))

    val result = getOutput()
    assertTrue(result.isEmpty())
  }

  @Test
  fun `quiet mode still shows failed events`() {
    val reporter = ProgressReporter(output = output, isTty = false, quiet = true)

    reporter.report(SyncEvent.FileFailed("file1", "test.txt", "Error"))

    val result = getOutput()
    assertContains(result, "test.txt")
    assertContains(result, "Error")
  }

  @Test
  fun `quiet mode still shows completed summary`() {
    val reporter = ProgressReporter(output = output, isTty = false, quiet = true)

    reporter.report(SyncEvent.Completed(10, 1024000, 2, Duration.ofSeconds(30)))

    val result = getOutput()
    assertContains(result, "Sync completed")
    assertContains(result, "10")
  }

  @Test
  fun `quiet mode still shows sync failed`() {
    val reporter = ProgressReporter(output = output, isTty = false, quiet = true)

    reporter.report(SyncEvent.Failed("Connection lost"))

    val result = getOutput()
    assertContains(result, "Sync failed")
    assertContains(result, "Connection lost")
  }

  // Verbose mode tests

  @Test
  fun `verbose mode shows queued files`() {
    val reporter = ProgressReporter(output = output, isTty = false, verbose = true)

    reporter.report(SyncEvent.FileQueued("file1", "document.pdf", 5000))

    val result = getOutput()
    assertContains(result, "Queued")
    assertContains(result, "document.pdf")
  }

  @Test
  fun `non-verbose mode hides queued files`() {
    val reporter = ProgressReporter(output = output, isTty = false, verbose = false)

    reporter.report(SyncEvent.FileQueued("file1", "document.pdf", 5000))

    val result = getOutput()
    assertTrue(result.isEmpty())
  }

  @Test
  fun `verbose mode shows download progress in non-TTY`() {
    val reporter = ProgressReporter(output = output, isTty = false, verbose = true)

    reporter.report(SyncEvent.FileDownloading("file1", "video.mp4", 512000, 1024000))

    val result = getOutput()
    assertContains(result, "Downloading")
    assertContains(result, "video.mp4")
    assertContains(result, "50%")
  }

  // Status display tests

  @Test
  fun `displayStatus shows all fields`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    // 500 MB
    val status =
      SyncStatusSnapshot(
        lastSyncTime = Instant.parse("2024-01-15T10:30:00Z"),
        filesTracked = 150,
        totalSize = 1024 * 1024 * 500L,
        pendingFiles = 5,
        failedFiles = 2,
      )

    reporter.displayStatus(status)

    val result = getOutput()
    assertContains(result, "Sync Status")
    assertContains(result, "2024-01-15")
    assertContains(result, "150")
    assertContains(result, "500.00 MB")
    assertContains(result, "5")
    assertContains(result, "2")
  }

  @Test
  fun `displayStatus shows Never when lastSyncTime is null`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    val status =
      SyncStatusSnapshot(
        lastSyncTime = null,
        filesTracked = 0,
        totalSize = 0,
        pendingFiles = 0,
        failedFiles = 0,
      )

    reporter.displayStatus(status)

    val result = getOutput()
    assertContains(result, "Never")
  }

  // Byte formatting tests

  @Test
  fun `completed event formats bytes correctly`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.report(SyncEvent.Completed(5, 1536 * 1024, 0, Duration.ofMinutes(2)))

    val result = getOutput()
    assertContains(result, "1.50 MB")
  }

  @Test
  fun `completed event shows duration`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.report(SyncEvent.Completed(10, 1024, 0, Duration.ofMinutes(5).plusSeconds(30)))

    val result = getOutput()
    assertContains(result, "PT5M30S")
  }

  // Error display tests

  @Test
  fun `printError shows error message with prefix`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.printError("Something went wrong")

    val result = getOutput()
    assertContains(result, "Error:")
    assertContains(result, "Something went wrong")
  }

  @Test
  fun `printError shows red text in TTY mode`() {
    val reporter = ProgressReporter(output = output, isTty = true)

    reporter.printError("Something went wrong")

    val result = getOutput()
    assertContains(result, "\u001B[31m") // Red color code
  }

  // Print helpers tests

  @Test
  fun `println outputs message with newline`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.println("Test message")

    val result = getOutput()
    assertTrue(result.endsWith("Test message\n") || result.endsWith("Test message\r\n"))
  }

  @Test
  fun `print outputs message without newline`() {
    val reporter = ProgressReporter(output = output, isTty = false)

    reporter.print("Test message")

    val result = getOutput()
    assertTrue(result == "Test message")
  }
}
