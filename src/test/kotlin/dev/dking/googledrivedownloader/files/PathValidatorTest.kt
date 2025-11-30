package dev.dking.googledrivedownloader.files

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PathValidatorTest {
  private lateinit var tempDir: Path

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("path-validator-test")
  }

  @AfterTest
  fun tearDown() {
    tempDir.toFile().deleteRecursively()
  }

  // validatePathWithinBase tests

  @Test
  fun `validatePathWithinBase - valid path within base directory`() {
    val outputPath = tempDir.resolve("subdir/file.txt")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validatePathWithinBase - valid nested path`() {
    val outputPath = tempDir.resolve("a/b/c/d/file.txt")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validatePathWithinBase - valid path at base directory`() {
    val outputPath = tempDir.resolve("file.txt")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validatePathWithinBase - rejects path traversal with dot dot`() {
    val outputPath = tempDir.resolve("subdir/../../../etc/passwd")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
    assertTrue(exception.message!!.contains("escapes base directory"))
  }

  @Test
  fun `validatePathWithinBase - rejects absolute path outside base`() {
    val outsidePath = Path.of("/tmp/malicious/file.txt")

    val result = PathValidator.validatePathWithinBase(outsidePath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
  }

  @Test
  fun `validatePathWithinBase - handles non-normalized paths correctly`() {
    // Path that normalizes to something inside the base directory
    val outputPath = tempDir.resolve("subdir/../otherdir/file.txt")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validatePathWithinBase - handles paths with current directory references`() {
    val outputPath = tempDir.resolve("./subdir/./file.txt")

    val result = PathValidator.validatePathWithinBase(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  // validateNoSymlinks tests

  @Test
  fun `validateNoSymlinks - valid path without symlinks`() {
    // Create a real directory structure
    val subdir = tempDir.resolve("subdir")
    Files.createDirectories(subdir)

    val outputPath = subdir.resolve("file.txt")

    val result = PathValidator.validateNoSymlinks(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validateNoSymlinks - valid path where parent does not exist yet`() {
    // Parent directory doesn't exist - no symlinks to check
    val outputPath = tempDir.resolve("nonexistent/subdir/file.txt")

    val result = PathValidator.validateNoSymlinks(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validateNoSymlinks - rejects path with symlink directory`() {
    // Create a target directory and a symlink to it
    val targetDir = tempDir.resolve("target")
    Files.createDirectories(targetDir)

    val symlinkDir = tempDir.resolve("symlink")
    Files.createSymbolicLink(symlinkDir, targetDir)

    val outputPath = symlinkDir.resolve("file.txt")

    val result = PathValidator.validateNoSymlinks(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
    assertTrue(exception.message!!.contains("Symlink detected"))
  }

  @Test
  fun `validateNoSymlinks - rejects nested symlink in path`() {
    // Create structure: subdir/symlink -> target
    val subdir = tempDir.resolve("subdir")
    Files.createDirectories(subdir)

    val targetDir = tempDir.resolve("target")
    Files.createDirectories(targetDir)

    val symlinkDir = subdir.resolve("symlink")
    Files.createSymbolicLink(symlinkDir, targetDir)

    val outputPath = symlinkDir.resolve("file.txt")

    val result = PathValidator.validateNoSymlinks(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
  }

  @Test
  fun `validateNoSymlinks - allows regular files`() {
    // Create a real file
    val subdir = tempDir.resolve("subdir")
    Files.createDirectories(subdir)
    val existingFile = subdir.resolve("existing.txt")
    Files.writeString(existingFile, "content")

    val outputPath = subdir.resolve("newfile.txt")

    val result = PathValidator.validateNoSymlinks(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  // validatePath (combined) tests

  @Test
  fun `validatePath - valid path passes both checks`() {
    val subdir = tempDir.resolve("subdir")
    Files.createDirectories(subdir)

    val outputPath = subdir.resolve("file.txt")

    val result = PathValidator.validatePath(outputPath, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validatePath - fails on path traversal first`() {
    val outputPath = tempDir.resolve("../../../etc/passwd")

    val result = PathValidator.validatePath(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
    assertTrue(exception.message!!.contains("escapes base directory"))
  }

  @Test
  fun `validatePath - fails on symlink when path traversal passes`() {
    val targetDir = tempDir.resolve("target")
    Files.createDirectories(targetDir)

    val symlinkDir = tempDir.resolve("symlink")
    Files.createSymbolicLink(symlinkDir, targetDir)

    val outputPath = symlinkDir.resolve("file.txt")

    val result = PathValidator.validatePath(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
    assertTrue(exception.message!!.contains("Symlink detected"))
  }

  @Test
  fun `validatePath - path traversal check runs before symlink check`() {
    // Even if there's a symlink, path traversal should be detected first
    val outputPath = tempDir.resolve("../../../etc/passwd")

    val result = PathValidator.validatePath(outputPath, tempDir)

    assertTrue(result.isFailure)
    val exception = result.exceptionOrNull()
    assertIs<PathValidationException>(exception)
    // Should fail on path traversal, not symlink
    assertTrue(exception.message!!.contains("escapes base directory"))
  }

  // Edge cases

  @Test
  fun `validatePathWithinBase - handles base directory itself`() {
    val result = PathValidator.validatePathWithinBase(tempDir, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `validateNoSymlinks - handles base directory itself`() {
    val result = PathValidator.validateNoSymlinks(tempDir, tempDir)

    assertTrue(result.isSuccess)
  }

  @Test
  fun `PathValidationException is a SecurityException`() {
    val exception = PathValidationException("test message")

    assertIs<SecurityException>(exception)
    assertEquals("test message", exception.message)
  }
}
