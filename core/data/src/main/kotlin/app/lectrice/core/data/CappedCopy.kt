package app.lectrice.core.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A stream held more than it may (a wrong server, a hostile provider, or a size that was a lie). */
class TooLargeException(val maxBytes: Long) : IOException("more than $maxBytes bytes")

/** Copies from sources the app doesn't control: what they send is counted, never trusted to end. */
object CappedCopy {
    private const val BUFFER_BYTES = 1 shl 16

    /**
     * Copies [input] to [output] and returns the bytes written. Throws [TooLargeException] as soon as the stream goes
     * past [maxBytes]; the caller deletes what was written. [onProgress] gets the running total after each write.
     */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, onProgress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (total + read > maxBytes) throw TooLargeException(maxBytes)
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
    }
}
