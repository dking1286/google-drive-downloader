package dev.dking.googledrivedownloader.api.impl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for Md5Utils.
 */
class Md5UtilsTest {
  @Test
  fun `computeMd5 returns correct hash for known content`() {
    // Create temp file with known content
    val tempFile = Files.createTempFile("md5-test", ".txt")
    try {
      // "Hello, World!" has a well-known MD5 hash
      Files.writeString(tempFile, "Hello, World!")

      val md5 = Md5Utils.computeMd5(tempFile)

      // MD5 of "Hello, World!" is 65a8e27d8879283831b664bd8b7f0ad4
      assertEquals("65a8e27d8879283831b664bd8b7f0ad4", md5)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns correct hash for empty file`() {
    val tempFile = Files.createTempFile("md5-empty-test", ".txt")
    try {
      // Empty file - write nothing

      val md5 = Md5Utils.computeMd5(tempFile)

      // MD5 of empty string is d41d8cd98f00b204e9800998ecf8427e
      assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns lowercase hex string`() {
    val tempFile = Files.createTempFile("md5-case-test", ".txt")
    try {
      Files.writeString(tempFile, "test content")

      val md5 = Md5Utils.computeMd5(tempFile)

      // Verify all characters are lowercase hex
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })
      assertEquals(32, md5.length, "MD5 hash should be 32 hex characters")
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 returns different hashes for different content`() {
    val tempFile1 = Files.createTempFile("md5-diff-test1", ".txt")
    val tempFile2 = Files.createTempFile("md5-diff-test2", ".txt")
    try {
      Files.writeString(tempFile1, "content one")
      Files.writeString(tempFile2, "content two")

      val md5First = Md5Utils.computeMd5(tempFile1)
      val md5Second = Md5Utils.computeMd5(tempFile2)

      assertNotEquals(md5First, md5Second, "Different content should produce different MD5 hashes")
    } finally {
      Files.deleteIfExists(tempFile1)
      Files.deleteIfExists(tempFile2)
    }
  }

  @Test
  fun `computeMd5 returns same hash for same content`() {
    val tempFile1 = Files.createTempFile("md5-same-test1", ".txt")
    val tempFile2 = Files.createTempFile("md5-same-test2", ".txt")
    try {
      val content = "identical content"
      Files.writeString(tempFile1, content)
      Files.writeString(tempFile2, content)

      val md5First = Md5Utils.computeMd5(tempFile1)
      val md5Second = Md5Utils.computeMd5(tempFile2)

      assertEquals(md5First, md5Second, "Same content should produce same MD5 hash")
    } finally {
      Files.deleteIfExists(tempFile1)
      Files.deleteIfExists(tempFile2)
    }
  }

  @Test
  fun `computeMd5 handles binary content correctly`() {
    val tempFile = Files.createTempFile("md5-binary-test", ".bin")
    try {
      // Write some binary content including null bytes
      val binaryContent = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
      Files.write(tempFile, binaryContent)

      val md5 = Md5Utils.computeMd5(tempFile)

      // Should complete without error and return valid hash
      assertEquals(32, md5.length)
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `computeMd5 handles large file correctly`() {
    val tempFile = Files.createTempFile("md5-large-test", ".bin")
    try {
      // Create a file larger than the 8192 byte buffer
      val largeContent = ByteArray(50000) { (it % 256).toByte() }
      Files.write(tempFile, largeContent)

      val md5 = Md5Utils.computeMd5(tempFile)

      // Should complete without error and return valid hash
      assertEquals(32, md5.length)
      assertTrue(md5.all { it in '0'..'9' || it in 'a'..'f' })

      // Verify it's deterministic
      val md5Again = Md5Utils.computeMd5(tempFile)
      assertEquals(md5, md5Again)
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
