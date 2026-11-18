package com.lechugones.cocina.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Lettuce-green fallback palette, used before Android 12 or when dynamic colour is off. */
private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF3A6A1F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBAF296),
        onPrimaryContainer = Color(0xFF0B2000),
        secondary = Color(0xFF56624A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDAE7C9),
        onSecondaryContainer = Color(0xFF141E0C),
        tertiary = Color(0xFF386665),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFBBECEA),
        onTertiaryContainer = Color(0xFF00201F),
        background = Color(0xFFF8FAF0),
        onBackground = Color(0xFF191D16),
        surface = Color(0xFFF8FAF0),
        onSurface = Color(0xFF191D16),
        surfaceVariant = Color(0xFFDFE4D7),
        onSurfaceVariant = Color(0xFF43483E),
        outline = Color(0xFF73796D),
        outlineVariant = Color(0xFFC3C8BB),
        surfaceDim = Color(0xFFD8DBD0),
        surfaceBright = Color(0xFFF8FAF0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F5EA),
        surfaceContainer = Color(0xFFECEFE4),
        surfaceContainerHigh = Color(0xFFE7E9DF),
        surfaceContainerHighest = Color(0xFFE1E4D9),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF9FD57D),
        onPrimary = Color(0xFF133800),
        primaryContainer = Color(0xFF225107),
        onPrimaryContainer = Color(0xFFBAF296),
        secondary = Color(0xFFBECBAE),
        onSecondary = Color(0xFF29341F),
        secondaryContainer = Color(0xFF3F4A34),
        onSecondaryContainer = Color(0xFFDAE7C9),
        tertiary = Color(0xFFA0CFCE),
        onTertiary = Color(0xFF003736),
        tertiaryContainer = Color(0xFF1E4E4D),
        onTertiaryContainer = Color(0xFFBBECEA),
        background = Color(0xFF11140E),
        onBackground = Color(0xFFE1E4D9),
        surface = Color(0xFF11140E),
        onSurface = Color(0xFFE1E4D9),
        surfaceVariant = Color(0xFF43483E),
        onSurfaceVariant = Color(0xFFC3C8BB),
        outline = Color(0xFF8D9286),
        outlineVariant = Color(0xFF43483E),
        surfaceDim = Color(0xFF11140E),
        surfaceBright = Color(0xFF373A33),
        surfaceContainerLowest = Color(0xFF0C0F09),
        surfaceContainerLow = Color(0xFF191D16),
        surfaceContainer = Color(0xFF1D211A),
        surfaceContainerHigh = Color(0xFF282B24),
        surfaceContainerHighest = Color(0xFF32362E),
    )

/** App theme: dynamic colour on Android 12+ (SPEC § 3.3), green palette otherwise. */
@Composable
fun CocinaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
