package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val EditorialLightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = OnTerracottaContainer,
    secondary = ArchivalSage,
    onSecondary = Color.White,
    secondaryContainer = ArchivalSageContainer,
    onSecondaryContainer = OnArchivalSageContainer,
    tertiary = OchreAmber,
    onTertiary = Color.White,
    tertiaryContainer = OchreContainer,
    onTertiaryContainer = OnOchreContainer,
    background = PaperBackground,
    onBackground = EspressoInk,
    surface = FolioSurface,
    onSurface = EspressoInk,
    surfaceVariant = FolioSurfaceContainerHigh,
    onSurfaceVariant = MutedSepiaInk,
    surfaceContainerLowest = FolioSurfaceElevated,
    surfaceContainerLow = PaperBackground,
    surfaceContainer = FolioSurface,
    surfaceContainerHigh = FolioSurfaceContainerHigh,
    outline = HairlineWarm,
    outlineVariant = HairlineWarm,
    error = CrimsonError,
    onError = Color.White,
    errorContainer = CrimsonErrorContainer
)

private val EditorialDarkColorScheme = darkColorScheme(
    primary = CopperPrimary,
    onPrimary = Color(0xFF281007),
    primaryContainer = CopperContainer,
    onPrimaryContainer = OnCopperContainer,
    secondary = LuminousSage,
    onSecondary = Color(0xFF0A261E),
    secondaryContainer = LuminousSageContainer,
    onSecondaryContainer = OnLuminousSageContainer,
    tertiary = WarmGold,
    onTertiary = Color(0xFF2E1E03),
    tertiaryContainer = WarmGoldContainer,
    onTertiaryContainer = OnWarmGoldContainer,
    background = ObsidianBackground,
    onBackground = WarmBoneInk,
    surface = CharcoalSurface,
    onSurface = WarmBoneInk,
    surfaceVariant = CharcoalSurfaceHigh,
    onSurfaceVariant = MutedWarmBone,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = CharcoalSurface,
    surfaceContainer = CharcoalSurfaceElevated,
    surfaceContainerHigh = CharcoalSurfaceHigh,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = DarkError,
    onError = Color(0xFF601410),
    errorContainer = DarkErrorContainer
)

val FolioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FolioGraphTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) EditorialDarkColorScheme else EditorialLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content
    )
}
