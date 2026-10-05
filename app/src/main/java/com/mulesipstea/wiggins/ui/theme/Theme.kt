package com.mulesipstea.wiggins.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.mulesipstea.wiggins.R

// Wiggins' palette: honey amber (the Waggle bees) on deep ink navy (Baker
// Street), with a teal accent. Fixed rather than dynamic, so status and
// warning colors read the same on every phone.

private val Light = lightColorScheme(
    primary = Color(0xFF7D5700),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA8),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF485A8C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF001A43),
    tertiary = Color(0xFF2F6B5A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8EDDA),
    onTertiaryContainer = Color(0xFF002018),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEEE0CF),
    onSurfaceVariant = Color(0xFF4E4539),
    outline = Color(0xFF807567),
    outlineVariant = Color(0xFFD1C5B4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2E9),
    surfaceContainer = Color(0xFFF5ECE3),
    surfaceContainerHigh = Color(0xFFEFE6DD),
    surfaceContainerHighest = Color(0xFFE9E1D8),
    inverseSurface = Color(0xFF34302A),
    inverseOnSurface = Color(0xFFF9EFE6),
    inversePrimary = Color(0xFFF9BC4D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF9BC4D),
    onPrimary = Color(0xFF422C00),
    primaryContainer = Color(0xFF5F4100),
    onPrimaryContainer = Color(0xFFFFDEA8),
    secondary = Color(0xFFB6C4FF),
    onSecondary = Color(0xFF18295A),
    secondaryContainer = Color(0xFF304072),
    onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = Color(0xFF9CD1BE),
    onTertiary = Color(0xFF03372C),
    tertiaryContainer = Color(0xFF1E5042),
    onTertiaryContainer = Color(0xFFB8EDDA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10131C),
    onBackground = Color(0xFFE2E2EC),
    surface = Color(0xFF10131C),
    onSurface = Color(0xFFE2E2EC),
    surfaceVariant = Color(0xFF444655),
    onSurfaceVariant = Color(0xFFC5C6D6),
    outline = Color(0xFF8F909F),
    outlineVariant = Color(0xFF444655),
    surfaceContainerLowest = Color(0xFF0B0E16),
    surfaceContainerLow = Color(0xFF181B25),
    surfaceContainer = Color(0xFF1C1F29),
    surfaceContainerHigh = Color(0xFF272A34),
    surfaceContainerHighest = Color(0xFF32353F),
    inverseSurface = Color(0xFFE2E2EC),
    inverseOnSurface = Color(0xFF2E3039),
    inversePrimary = Color(0xFF7D5700),
)

/** Colors for the connection state dot. */
object StatusColors {
    val ColorScheme.connected get() = tertiary
    val ColorScheme.connecting get() = primary
    val ColorScheme.disconnected get() = error
}

// Libre Baskerville (SIL OFL, licenses/LibreBaskerville-OFL.txt) for the app's
// name and screen titles: a Victorian serif. Everything people read at length,
// the conversation included, stays in the system font.
private val Baskerville = FontFamily(
    Font(R.font.libre_baskerville, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.libre_baskerville, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val Titles = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = Baskerville),
        titleLarge = titleLarge.copy(fontFamily = Baskerville),
        titleMedium = titleMedium.copy(fontFamily = Baskerville, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun WigginsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Titles, content = content)
}
