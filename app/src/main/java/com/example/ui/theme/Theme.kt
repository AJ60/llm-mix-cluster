package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val SilicaDarkColorScheme = darkColorScheme(
    primary = SilicaEmerald,
    onPrimary = SilicaOnEmerald,
    primaryContainer = SilicaEmeraldContainer,
    onPrimaryContainer = SilicaOnEmeraldContainer,
    secondary = SilicaCyan,
    onSecondary = SilicaOnCyan,
    secondaryContainer = SilicaCyanContainer,
    onSecondaryContainer = SilicaOnCyanContainer,
    tertiary = SilicaAmber,
    onTertiary = SilicaObsidian,
    tertiaryContainer = SilicaAmberContainer,
    onTertiaryContainer = SilicaOnAmberContainer,
    error = SilicaCrimson,
    onError = SilicaIce,
    errorContainer = SilicaCrimsonContainer,
    onErrorContainer = SilicaOnCrimsonContainer,
    background = SilicaObsidian,
    onBackground = SilicaIce,
    surface = SilicaCarbon,
    onSurface = SilicaIce,
    surfaceVariant = SilicaGraphite,
    onSurfaceVariant = SilicaSteel,
    surfaceContainerLowest = SilicaTerminalBg,
    surfaceContainerLow = SilicaCarbon,
    surfaceContainer = SilicaGraphite,
    surfaceContainerHigh = SilicaSlateElevated,
    outline = SilicaOutline,
    outlineVariant = SilicaHairline
)

val SilicaLightColorScheme = lightColorScheme(
    primary = StudioLightPrimary,
    onPrimary = StudioLightOnPrimary,
    primaryContainer = StudioLightPrimaryContainer,
    onPrimaryContainer = StudioLightOnPrimaryContainer,
    secondary = StudioLightSecondary,
    onSecondary = StudioLightOnSecondary,
    secondaryContainer = StudioLightSecondaryContainer,
    onSecondaryContainer = StudioLightOnSecondaryContainer,
    tertiary = StudioLightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = StudioLightBackground,
    onBackground = StudioLightOnBackground,
    surface = StudioLightSurface,
    onSurface = StudioLightOnSurface,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFF1F5F9),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = StudioLightOutline,
    outlineVariant = Color(0xFFE2E8F0)
)

val SilicaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun SilicaClusterTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SilicaDarkColorScheme else SilicaLightColorScheme,
        typography = Typography,
        shapes = SilicaShapes,
        content = content
    )
}

@Composable
fun ClusterLabTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    SilicaClusterTheme(darkTheme = darkTheme, content = content)
}
