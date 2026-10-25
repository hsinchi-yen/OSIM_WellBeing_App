package com.example.osimsofa.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original "quiet luxury" palette: deep ink surfaces, champagne accent, sage/sky secondaries. */
object SofaColors {
    val Ink = Color(0xFF0E1013)
    val Surface = Color(0xFF171A20)
    val SurfaceHigh = Color(0xFF1F242C)
    val SurfaceHigher = Color(0xFF28303A)
    val Champagne = Color(0xFFD9BB8C)
    val ChampagneDeep = Color(0xFFB8955F)
    val OnChampagne = Color(0xFF1A1407)
    val Sage = Color(0xFF8DB7A5)
    val Sky = Color(0xFF9FB4D8)
    val Coral = Color(0xFFE26D5A)
    val CoralDeep = Color(0xFF3A1F1B)
    val Text = Color(0xFFF1EDE6)
    val Muted = Color(0xFFA7ADB4)
    val Line = Color(0xFF2C323B)
}

private val Scheme = darkColorScheme(
    primary = SofaColors.Champagne,
    onPrimary = SofaColors.OnChampagne,
    primaryContainer = SofaColors.ChampagneDeep,
    onPrimaryContainer = SofaColors.OnChampagne,
    secondary = SofaColors.Sage,
    onSecondary = SofaColors.Ink,
    secondaryContainer = SofaColors.SurfaceHigher,
    onSecondaryContainer = SofaColors.Text,
    tertiary = SofaColors.Sky,
    onTertiary = SofaColors.Ink,
    background = SofaColors.Ink,
    onBackground = SofaColors.Text,
    surface = SofaColors.Surface,
    onSurface = SofaColors.Text,
    surfaceVariant = SofaColors.SurfaceHigh,
    onSurfaceVariant = SofaColors.Muted,
    outline = SofaColors.Line,
    error = SofaColors.Coral,
    onError = SofaColors.Ink,
)

/** Main information 16–20 sp; nothing the user must read is below 13 sp (improve-plan §4.4). */
private val Type = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SofaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shape, content = content)
}
