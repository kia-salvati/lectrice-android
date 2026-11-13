package app.lectrice.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, family: FontFamily = FontFamily.Default, italic: Boolean = false) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Design brief §9.3 with Quiet's denser rows; reader text §8.4. Roboto is the system sans, Noto Serif the system serif. */
@Immutable
class LectriceType {
    val titleL = style(22, 28, FontWeight.SemiBold)
    val titleM = style(18, 24, FontWeight.SemiBold)
    val body = style(16, 24)
    val bodyS = style(14, 20)
    val label = style(14, 20, FontWeight.Medium)
    val labelL = style(16, 24, FontWeight.Medium)
    val caption = style(12, 16)
    val rowTitle = style(16, 20, FontWeight.Medium)
    val rowMeta = style(14, 18)
    val libraryTitle = style(20, 24, FontWeight.SemiBold)
    val sectionHeader = style(14, 20, FontWeight.SemiBold)

    val reading = style(19, 32, family = FontFamily.Serif)
    val readingH1 = style(26, 34, FontWeight.SemiBold, FontFamily.Serif)
    val readingH2 = style(22, 30, FontWeight.SemiBold, FontFamily.Serif)
    val readingH3 = style(19, 28, FontWeight.Bold, FontFamily.Serif)
    val sample = style(17, 26, family = FontFamily.Serif, italic = true)
    val welcomeTitle = style(32, 40, FontWeight.SemiBold, FontFamily.Serif)
    val code = style(14, 20, family = FontFamily.Monospace)
    val cue = style(13, 18)

    /** The serif at a chosen size (text-size preview, reader scaling). */
    fun serif(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
        reading.copy(fontSize = size, lineHeight = line, fontWeight = weight)
}
