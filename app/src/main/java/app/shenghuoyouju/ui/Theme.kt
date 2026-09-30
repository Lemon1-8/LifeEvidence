package app.shenghuoyouju.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF256455),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EADF),
    onPrimaryContainer = Color(0xFF133E32),
    secondary = Color(0xFF766146),
    secondaryContainer = Color(0xFFF0E4D2),
    onSecondaryContainer = Color(0xFF503D25),
    background = Color(0xFFF8F8F2),
    surface = Color(0xFFF8F8F2),
    surfaceContainer = Color(0xFFF0F1E9),
    surfaceContainerLow = Color(0xFFF3F4ED),
    surfaceContainerHigh = Color(0xFFEAECE3),
    surfaceContainerHighest = Color(0xFFE3E6DC),
    onBackground = Color(0xFF1F2B25),
    onSurface = Color(0xFF1F2B25),
    onSurfaceVariant = Color(0xFF637068),
    outline = Color(0xFF7C8980),
    outlineVariant = Color(0xFFD7DED4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD2BA),
    onPrimary = Color(0xFF07392B),
    primaryContainer = Color(0xFF214E3E),
    onPrimaryContainer = Color(0xFFD2EEDE),
    secondary = Color(0xFFE0C29A),
    secondaryContainer = Color(0xFF50412E),
    onSecondaryContainer = Color(0xFFF0DDBF),
    background = Color(0xFF141C18),
    surface = Color(0xFF141C18),
    surfaceContainer = Color(0xFF1E2922),
    surfaceContainerLow = Color(0xFF19221C),
    surfaceContainerHigh = Color(0xFF27322B),
    surfaceContainerHighest = Color(0xFF303E34),
    onBackground = Color(0xFFE1E8DE),
    onSurface = Color(0xFFE1E8DE),
    onSurfaceVariant = Color(0xFFAFBFB3),
    outline = Color(0xFF819287),
    outlineVariant = Color(0xFF3E5044),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 31.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 29.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
fun LifeEvidenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = AppTypography, content = content)
}
