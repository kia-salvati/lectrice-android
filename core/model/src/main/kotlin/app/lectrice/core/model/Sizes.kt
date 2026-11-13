package app.lectrice.core.model

/** Sizes as Android's own storage screens show them (MB = 10⁶ bytes, README §4.7): "370 MB", "1 GB", "23.4 GB". */
object Sizes {
    private const val KB = 1_000.0
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0
    private const val TEN = 10.0

    fun format(bytes: Long): String = when {
        bytes >= GB -> trim(bytes / GB) + " GB"
        bytes >= MB -> "${Math.round(bytes / MB)} MB"
        bytes >= KB -> "${Math.round(bytes / KB)} kB"
        else -> "$bytes B"
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * TEN) / TEN
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
