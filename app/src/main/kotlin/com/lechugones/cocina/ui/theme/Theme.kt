package com.lechugones.cocina.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/*
 * "Cocina mediterránea": tomato red, olive green and saffron on a cream page, with a warm
 * espresso dark mode. Headings use Noto Serif for a recipe-book feel; body text stays in the
 * readable sans-serif. No bundled fonts, nothing downloaded.
 */

/**
 * Noto Serif, read from the system font files: some phones (OnePlus) swap the "serif" family for
 * their own sans-serif, so asking for [FontFamily.Serif] is not enough. Falls back to it when the
 * files are missing.
 */
private val HeadingFamily: FontFamily =
    run {
        val regular = File("/system/fonts/NotoSerif-Regular.ttf")
        val bold = File("/system/fonts/NotoSerif-Bold.ttf")
        if (regular.canRead() && bold.canRead()) {
            FontFamily(
                Font(regular, FontWeight.Normal),
                Font(bold, FontWeight.SemiBold),
                Font(bold, FontWeight.Bold),
            )
        } else {
            FontFamily.Serif
        }
    }

private val Tomato = Color(0xFFB3412A)
private val TomatoLight = Color(0xFFFFB4A2)
private val Olive = Color(0xFF586425)
private val OliveLight = Color(0xFFC2CF84)
private val Saffron = Color(0xFF8C5A00)
private val SaffronLight = Color(0xFFFFB955)

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Tomato,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD2),
        onPrimaryContainer = Color(0xFF3D0700),
        secondary = Olive,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDDEBA0),
        onSecondaryContainer = Color(0xFF181E00),
        tertiary = Saffron,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDDB4),
        onTertiaryContainer = Color(0xFF2C1800),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFF8F1),
        onBackground = Color(0xFF231915),
        surface = Color(0xFFFFF8F1),
        onSurface = Color(0xFF231915),
        surfaceVariant = Color(0xFFF4DED7),
        onSurfaceVariant = Color(0xFF53433E),
        outline = Color(0xFF85736D),
        outlineVariant = Color(0xFFD8C2BB),
        surfaceDim = Color(0xFFE8D6CF),
        surfaceBright = Color(0xFFFFF8F1),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1EB),
        surfaceContainer = Color(0xFFFCEAE3),
        surfaceContainerHigh = Color(0xFFF6E4DD),
        surfaceContainerHighest = Color(0xFFF0DFD8),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = TomatoLight,
        onPrimary = Color(0xFF5F1500),
        primaryContainer = Color(0xFF8D2C17),
        onPrimaryContainer = Color(0xFFFFDAD2),
        secondary = OliveLight,
        onSecondary = Color(0xFF2C3400),
        secondaryContainer = Color(0xFF414B0E),
        onSecondaryContainer = Color(0xFFDDEBA0),
        tertiary = SaffronLight,
        onTertiary = Color(0xFF4A2900),
        tertiaryContainer = Color(0xFF6A4300),
        onTertiaryContainer = Color(0xFFFFDDB4),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF1B120F),
        onBackground = Color(0xFFF1DFD9),
        surface = Color(0xFF1B120F),
        onSurface = Color(0xFFF1DFD9),
        surfaceVariant = Color(0xFF53433E),
        onSurfaceVariant = Color(0xFFD8C2BB),
        outline = Color(0xFFA08C86),
        outlineVariant = Color(0xFF53433E),
        surfaceDim = Color(0xFF1B120F),
        surfaceBright = Color(0xFF433733),
        surfaceContainerLowest = Color(0xFF150D0A),
        surfaceContainerLow = Color(0xFF231A16),
        surfaceContainer = Color(0xFF281E1A),
        surfaceContainerHigh = Color(0xFF332824),
        surfaceContainerHighest = Color(0xFF3E322E),
    )

private val Base = Typography()

private fun TextStyle.serif(weight: FontWeight = FontWeight.SemiBold) = copy(fontFamily = HeadingFamily, fontWeight = weight)

/** Serif headings and titles, sans-serif body and labels. */
private val CocinaTypography =
    Typography(
        displayLarge = Base.displayLarge.serif(),
        displayMedium = Base.displayMedium.serif(),
        displaySmall = Base.displaySmall.serif(),
        headlineLarge = Base.headlineLarge.serif(FontWeight.Bold),
        headlineMedium = Base.headlineMedium.serif(FontWeight.Bold),
        headlineSmall = Base.headlineSmall.serif(FontWeight.Bold),
        titleLarge = Base.titleLarge.serif(FontWeight.Bold).copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleMedium = Base.titleMedium.serif(),
        titleSmall = Base.titleSmall.serif().copy(fontSize = 16.sp, letterSpacing = 0.2.sp),
        bodyLarge = Base.bodyLarge,
        bodyMedium = Base.bodyMedium,
        bodySmall = Base.bodySmall,
        labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = Base.labelSmall,
    )

/** Soft, generous corners: cards and sheets feel like recipe cards rather than terminal boxes. */
private val CocinaShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/**
 * App theme (SPEC § 3.3): the app's own warm palette in light and dark. Wallpaper-based dynamic
 * colour is off by design, so the app looks like itself on every phone.
 */
@Composable
fun CocinaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CocinaTypography,
        shapes = CocinaShapes,
        content = content,
    )
}
