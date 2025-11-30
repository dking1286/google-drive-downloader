package dev.dking.googledrivedownloader.files

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared path validation utilities for preventing path traversal and symlink attacks.
 *
 * These validations ensure that file operations are performed only within a designated
 * base directory, protecting against security vulnerabilities where malicious file names
 * could redirect writes to arbitrary locations on the filesystem.
 */
object PathValidator {
  /**
   * Validates that outputPath is within baseDirectory (no path traversal).
   *
   * This prevents path traversal attacks where malicious file names containing ".." or
   * absolute paths could write outside the intended download directory.
   *
   * @param outputPath The path to validate
   * @param baseDirectory The base directory that outputPath must be within
   * @return Result.success if valid, Result.failure with descriptive error otherwise
   */
  fun validatePathWithinBase(
    outputPath: Path,
    baseDirectory: Path,
  ): Result<Unit> {
    val normalizedOutput = outputPath.normalize().toAbsolutePath()
    val normalizedBase = baseDirectory.normalize().toAbsolutePath()

    return if (normalizedOutput.startsWith(normalizedBase)) {
      Result.success(Unit)
    } else {
      Result.failure(
        PathValidationException(
          "Output path $outputPath escapes base directory $baseDirectory",
        ),
      )
    }
  }

  /**
   * Validates that no component of the path is a symbolic link.
   *
   * This prevents symlink attacks where an attacker could create a symlink pointing
   * to a sensitive location, causing file operations to affect unintended paths.
   *
   * @param outputPath The path to validate
   * @param baseDirectory The base directory from which to start checking
   * @return Result.success if no symlinks, Result.failure with descriptive error otherwise
   */
  fun validateNoSymlinks(
    outputPath: Path,
    baseDirectory: Path,
  ): Result<Unit> {
    val normalizedPath = outputPath.normalize().toAbsolutePath()
    val normalizedBase = baseDirectory.normalize().toAbsolutePath()

    // Check each existing path component from base directory to output path
    var currentPath = normalizedBase
    for (component in normalizedBase.relativize(normalizedPath)) {
      currentPath = currentPath.resolve(component)
      if (Files.exists(currentPath) && Files.isSymbolicLink(currentPath)) {
        return Result.failure(
          PathValidationException(
            "Symlink detected in path: $currentPath. " +
              "Symlinks are not allowed for security reasons.",
          ),
        )
      }
    }

    return Result.success(Unit)
  }

  /**
   * Combines both validations: path traversal and symlink checks.
   *
   * This is the recommended method to use for most validation scenarios as it
   * provides comprehensive protection against both path traversal and symlink attacks.
   *
   * @param outputPath The path to validate
   * @param baseDirectory The base directory that outputPath must be within
   * @return Result.success if both pass, Result.failure with first error otherwise
   */
  fun validatePath(
    outputPath: Path,
    baseDirectory: Path,
  ): Result<Unit> {
    return validatePathWithinBase(outputPath, baseDirectory).fold(
      onSuccess = { validateNoSymlinks(outputPath, baseDirectory) },
      onFailure = { Result.failure(it) },
    )
  }
}

/**
 * Exception thrown when path validation fails.
 */
class PathValidationException(message: String) : SecurityException(message)
