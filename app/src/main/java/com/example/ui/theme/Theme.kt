package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun SoundSyncTheme(
  themeMode: ThemeMode = ThemeMode.CURRENT,
  content: @Composable () -> Unit,
) {
  setActiveAppearancePalette(themeMode)

  val colorScheme = darkColorScheme(
    primary = if (themeMode == ThemeMode.DARK) BloodRedPrimary else DeckACyan,
    onPrimary = if (themeMode == ThemeMode.DARK) Color.White else DjObsidian,
    primaryContainer = DjSurfaceElevated,
    onPrimaryContainer = if (themeMode == ThemeMode.DARK) BloodRedPrimary else DeckACyan,
    secondary = DeckBPink,
    onSecondary = Color.White,
    secondaryContainer = DjSurfaceElevated,
    onSecondaryContainer = DeckBPink,
    tertiary = NeonGreen,
    onTertiary = DjObsidian,
    background = DjObsidian,
    onBackground = TextPrimary,
    surface = DjSurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = DjSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = DjSurfaceBorder,
    error = NeonRed,
    onError = Color.White
  )

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
