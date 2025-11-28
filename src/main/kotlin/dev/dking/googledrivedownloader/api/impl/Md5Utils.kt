package dev.dking.googledrivedownloader.api.impl

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Utility functions for MD5 hash computation.
 */
object Md5Utils {
  /**
   * Compute MD5 hash of a file and return it as a lowercase hex string.
   *
   * @param path Path to the file
   * @return MD5 hash as lowercase hex string
   */
  fun computeMd5(path: Path): String {
    val md = MessageDigest.getInstance("MD5")
    Files.newInputStream(path).use { inputStream ->
      val buffer = ByteArray(8192)
      var bytesRead: Int
      while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        md.update(buffer, 0, bytesRead)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
