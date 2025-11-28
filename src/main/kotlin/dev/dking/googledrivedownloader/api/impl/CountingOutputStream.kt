package dev.dking.googledrivedownloader.api.impl

import java.io.OutputStream

/**
 * An OutputStream wrapper that counts the number of bytes written.
 * Used for accurate progress reporting when total file size is unknown.
 *
 * @param delegate The underlying output stream to write to
 */
class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
  /** The total number of bytes written to this stream */
  var bytesWritten: Long = 0L
    private set

  override fun write(b: Int) {
    delegate.write(b)
    bytesWritten++
  }

  override fun write(b: ByteArray) {
    delegate.write(b)
    bytesWritten += b.size
  }

  override fun write(
    b: ByteArray,
    off: Int,
    len: Int,
  ) {
    delegate.write(b, off, len)
    bytesWritten += len
  }

  override fun flush() {
    delegate.flush()
  }

  override fun close() {
    delegate.close()
  }
}
