package app.lectrice.core.designsystem

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Every text/background pair of design brief §9.2 stays ≥ 4.5:1 in light, dark and black (README §4.3). */
class ContrastTest {
    @Test
    fun lightPairsReadable() = check("light", Tokens.light)

    @Test
    fun darkPairsReadable() = check("dark", Tokens.dark)

    @Test
    fun blackPairsReadable() = check("black", Tokens.black)

    private fun check(name: String, set: TokenSet) {
        Tokens.textPairs(set).forEach { (fg, bg) ->
            val ratio = contrast(fg, bg)
            assertTrue("$name: ${hex(fg)} on ${hex(bg)} is ${"%.2f".format(ratio)}:1", ratio >= MIN_CONTRAST)
        }
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + OFFSET) / (minOf(la, lb) + OFFSET)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= LINEAR_CUTOFF) value / LINEAR_DIVISOR else ((value + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
        }
        return RED_WEIGHT * channel(16) + GREEN_WEIGHT * channel(8) + BLUE_WEIGHT * channel(0)
    }

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val OFFSET = 0.05
        const val LINEAR_CUTOFF = 0.03928
        const val LINEAR_DIVISOR = 12.92
        const val GAMMA_OFFSET = 0.055
        const val GAMMA_DIVISOR = 1.055
        const val GAMMA = 2.4
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
    }
}
