package dev.dking.googledrivedownloader.api.impl

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for CountingOutputStream.
 */
class CountingOutputStreamTest {
  @Test
  fun `starts with zero bytes written`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    assertEquals(0L, countingStream.bytesWritten)
  }

  @Test
  fun `counts single byte writes`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    countingStream.write(65) // 'A'
    assertEquals(1L, countingStream.bytesWritten)

    countingStream.write(66) // 'B'
    assertEquals(2L, countingStream.bytesWritten)

    countingStream.write(67) // 'C'
    assertEquals(3L, countingStream.bytesWritten)

    // Verify data was passed through to delegate
    assertEquals("ABC", delegate.toString(Charsets.UTF_8))
  }

  @Test
  fun `counts byte array writes`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    val data = "Hello, World!".toByteArray(Charsets.UTF_8)
    countingStream.write(data)

    assertEquals(data.size.toLong(), countingStream.bytesWritten)
    assertEquals("Hello, World!", delegate.toString(Charsets.UTF_8))
  }

  @Test
  fun `counts partial byte array writes`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    val data = "Hello, World!".toByteArray(Charsets.UTF_8)
    // Write only "Hello" (first 5 bytes)
    countingStream.write(data, 0, 5)

    assertEquals(5L, countingStream.bytesWritten)
    assertEquals("Hello", delegate.toString(Charsets.UTF_8))
  }

  @Test
  fun `accumulates bytes across multiple writes`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    // Mix of different write methods
    countingStream.write(65) // 1 byte
    countingStream.write("test".toByteArray(Charsets.UTF_8)) // 4 bytes
    countingStream.write("partial".toByteArray(Charsets.UTF_8), 0, 3) // 3 bytes

    assertEquals(8L, countingStream.bytesWritten)
    assertEquals("Atestpar", delegate.toString(Charsets.UTF_8))
  }

  @Test
  fun `delegates flush correctly`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    countingStream.write("test".toByteArray(Charsets.UTF_8))
    countingStream.flush()

    // Verify data is available after flush
    assertEquals("test", delegate.toString(Charsets.UTF_8))
  }

  @Test
  fun `delegates close correctly`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    countingStream.write("test".toByteArray(Charsets.UTF_8))
    countingStream.close()

    // bytesWritten should still be accessible after close
    assertEquals(4L, countingStream.bytesWritten)
  }

  @Test
  fun `handles empty writes`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    countingStream.write(ByteArray(0))
    assertEquals(0L, countingStream.bytesWritten)

    countingStream.write("test".toByteArray(Charsets.UTF_8), 0, 0)
    assertEquals(0L, countingStream.bytesWritten)
  }

  @Test
  fun `handles large data correctly`() {
    val delegate = ByteArrayOutputStream()
    val countingStream = CountingOutputStream(delegate)

    // Write 100KB of data
    val largeData = ByteArray(100_000) { (it % 256).toByte() }
    countingStream.write(largeData)

    assertEquals(100_000L, countingStream.bytesWritten)
    assertEquals(100_000, delegate.size())
  }
}
