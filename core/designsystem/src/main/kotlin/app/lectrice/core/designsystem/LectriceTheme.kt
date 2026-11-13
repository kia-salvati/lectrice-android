package app.lectrice.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import app.lectrice.core.model.ThemeSetting

/**
 * The only file with colour values (README §4.3; v2 brief §4). Kept as ARGB `Long`s so the JVM contrast test can read
 * them without Android.
 */
@Immutable
data class TokenSet(
    val bg: Long,
    val bg2: Long,
    val fg: Long,
    val fg2: Long,
    val line: Long,
    val accent: Long,
    val onAccent: Long,
    val hl: Long,
    val hlFg: Long,
    val errBg: Long,
    val errFg: Long,
    val accentContainer: Long,
    val onAccentContainer: Long,
    val warnBg: Long,
    val warnFg: Long,
    val okFg: Long,
    val inverseBg: Long,
    val inverseFg: Long,
    val scrim: Long,
    /** Cover tile hues 0–5, background and text (first brief §9.1). */
    val tiles: List<Long>,
    val onTiles: List<Long>,
    val isDark: Boolean,
)

object Tokens {
    val light = TokenSet(
        bg = 0xFFFFFFFF, bg2 = 0xFFF3F3EF, fg = 0xFF1C1F24, fg2 = 0xFF5B636D, line = 0xFFD3D3CD,
        accent = 0xFF1D5FAE, onAccent = 0xFFFFFFFF, hl = 0xFFFFE58F, hlFg = 0xFF1C1F24,
        errBg = 0xFFFBE7E5, errFg = 0xFF8B1A12, accentContainer = 0xFFDCE8F7, onAccentContainer = 0xFF0E3A6E,
        warnBg = 0xFFFFF1D0, warnFg = 0xFF6B4A00, okFg = 0xFF1E6B35, inverseBg = 0xFF1C1F24, inverseFg = 0xFFFFFFFF,
        scrim = 0x521C1F24,
        tiles = listOf(0xFFDCE8F7, 0xFFE5F0DC, 0xFFF6E3D3, 0xFFEADFF3, 0xFFDCEFEF, 0xFFF3E6C4),
        onTiles = listOf(0xFF0E3A6E, 0xFF22461A, 0xFF5A2E0B, 0xFF3D2257, 0xFF0F4444, 0xFF4F3A00),
        isDark = false,
    )

    val dark = TokenSet(
        bg = 0xFF121417, bg2 = 0xFF1C1F24, fg = 0xFFE8E6E1, fg2 = 0xFFA8ADB5, line = 0xFF363A41,
        accent = 0xFF8AB8FF, onAccent = 0xFF0B1A33, hl = 0xFF4D4214, hlFg = 0xFFFFF6D8,
        errBg = 0xFF44181A, errFg = 0xFFF4B1AD, accentContainer = 0xFF22344F, onAccentContainer = 0xFFD6E6FF,
        warnBg = 0xFF3A2E0E, warnFg = 0xFFF5D27A, okFg = 0xFF8FD6A3, inverseBg = 0xFFE8E6E1, inverseFg = 0xFF121417,
        scrim = 0x80000000,
        tiles = listOf(0xFF22344F, 0xFF25361E, 0xFF40291A, 0xFF33264A, 0xFF163838, 0xFF3B2F10),
        onTiles = listOf(0xFFD6E6FF, 0xFFCDE8BF, 0xFFF6D5BC, 0xFFE6D6F5, 0xFFBFE6E6, 0xFFF1DFA8),
        isDark = true,
    )

    /** True black for OLED: its own base values, the rest from dark. */
    val black = dark.copy(
        bg = 0xFF000000, bg2 = 0xFF121212, fg = 0xFFE6E3DD, fg2 = 0xFFA3A3A3, line = 0xFF2A2A2A,
        hl = 0xFF3D3410, hlFg = 0xFFFFF3CC,
    )

    /** The text/background pairs the design brief §9.2 measured; every one must stay ≥ 4.5:1. */
    fun textPairs(set: TokenSet): List<Pair<Long, Long>> = listOf(
        set.fg to set.bg, set.fg to set.bg2, set.fg2 to set.bg, set.fg2 to set.bg2,
        set.accent to set.bg, set.accent to set.bg2, set.onAccent to set.accent,
        set.hlFg to set.hl, set.errFg to set.errBg, set.errFg to set.bg, set.errFg to set.bg2,
        set.onAccentContainer to set.accentContainer, set.warnFg to set.warnBg, set.okFg to set.bg, set.okFg to set.bg2,
        set.inverseFg to set.inverseBg,
    ) + set.onTiles.zip(set.tiles)
}

@Immutable
class LectriceColors(set: TokenSet) {
    val bg = Color(set.bg)
    val bg2 = Color(set.bg2)
    val fg = Color(set.fg)
    val fg2 = Color(set.fg2)
    val line = Color(set.line)
    val accent = Color(set.accent)
    val onAccent = Color(set.onAccent)
    val hl = Color(set.hl)
    val hlFg = Color(set.hlFg)
    val errBg = Color(set.errBg)
    val errFg = Color(set.errFg)
    val accentContainer = Color(set.accentContainer)
    val onAccentContainer = Color(set.onAccentContainer)
    val warnBg = Color(set.warnBg)
    val warnFg = Color(set.warnFg)
    val okFg = Color(set.okFg)
    val inverseBg = Color(set.inverseBg)
    val inverseFg = Color(set.inverseFg)
    val scrim = Color(set.scrim)
    val tiles = set.tiles.map(::Color)
    val onTiles = set.onTiles.map(::Color)
    val isDark = set.isDark

    fun tile(hue: Int): Color = tiles[hue.mod(tiles.size)]

    fun onTile(hue: Int): Color = onTiles[hue.mod(onTiles.size)]
}

private val lightColors = LectriceColors(Tokens.light)
private val darkColors = LectriceColors(Tokens.dark)
private val blackColors = LectriceColors(Tokens.black)
private val defaultType = LectriceType()

val LocalLectriceColors = staticCompositionLocalOf { lightColors }
val LocalLectriceType = staticCompositionLocalOf { defaultType }

object LectriceTheme {
    val colors: LectriceColors
        @Composable get() = LocalLectriceColors.current
    val type: LectriceType
        @Composable get() = LocalLectriceType.current
}

/** System follows the phone and uses dark (not black). Also sets the status/navigation bar icon colours. */
@Composable
fun LectriceTheme(theme: ThemeSetting, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeSetting.System -> isSystemInDarkTheme()
        ThemeSetting.Light -> false
        ThemeSetting.Dark, ThemeSetting.Black -> true
    }
    val colors = when {
        theme == ThemeSetting.Black -> blackColors
        dark -> darkColors
        else -> lightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect { setSystemBarIconsLight(view, lightIcons = dark) }
    }
    CompositionLocalProvider(
        LocalLectriceColors provides colors,
        LocalLectriceType provides defaultType,
        LocalReducedMotion provides rememberReducedMotion(),
        LocalContentColor provides colors.fg,
        LocalIndication provides ripple(),
        content = content,
    )
}

private fun setSystemBarIconsLight(view: View, lightIcons: Boolean) {
    val window = view.context.findActivity()?.window ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (lightIcons) 0 else mask, mask)
    } else {
        @Suppress("DEPRECATION")
        val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        @Suppress("DEPRECATION")
        view.systemUiVisibility = if (lightIcons) view.systemUiVisibility and flags.inv() else view.systemUiVisibility or flags
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
