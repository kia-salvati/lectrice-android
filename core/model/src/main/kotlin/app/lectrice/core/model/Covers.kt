package app.lectrice.core.model

/** Generated cover tiles (file 02 §3.4): initials and one of six hues. */
object Covers {
    const val HUES = 6

    /** First letters of the first two words that start with a letter; one word → its first two letters. */
    fun initials(title: String): String {
        val words = title.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetter() == true }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 -> words[0].filter(Char::isLetter).take(2).uppercase()
            else -> "?"
        }
    }

    /** A stable hash (not `String.hashCode`'s sign quirks) mod 6. */
    fun hue(title: String): Int = title.fold(0) { hash, char -> (hash * 31 + char.code) and 0x7fffffff } % HUES
}
