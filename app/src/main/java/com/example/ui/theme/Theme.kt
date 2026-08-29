package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DjProColorScheme = darkColorScheme(
  primary = DeckACyan,
  onPrimary = DjObsidian,
  primaryContainer = DjSurfaceElevated,
  onPrimaryContainer = DeckACyan,
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

@Composable
fun SoundSyncTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = DjProColorScheme,
    typography = Typography,
    content = content
  )
}

