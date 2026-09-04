package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Main Theme object and Composable entry point for SoundSync.
 */
object SoundSyncTheme {
    val current: SoundSyncThemeSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalSoundSyncTheme.current

    val isPro: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalSoundSyncTheme.current.isPro

    val libraryDensity: ProLibraryDensity
        @Composable
        @ReadOnlyComposable
        get() = LocalLibraryDensity.current

    val Default: SoundSyncThemeSpec get() = DefaultThemeSpec
    val Pro: SoundSyncThemeSpec get() = ProThemeSpec

    @Composable
    operator fun invoke(
        themeMode: ThemeMode = ThemeMode.DEFAULT,
        libraryDensity: ProLibraryDensity = ProLibraryDensity.COMPACT,
        content: @Composable () -> Unit,
    ) {
        setActiveAppearancePalette(themeMode)

        val spec = when (themeMode) {
            ThemeMode.PRO -> ProThemeSpec
            ThemeMode.DARK -> DefaultThemeSpec.copy(
                background = Color(0xFF050505),
                surface = Color(0xFF101010),
                accent = BloodRedPrimary
            )
            else -> DefaultThemeSpec
        }

        val colorScheme = darkColorScheme(
            primary = spec.accent,
            onPrimary = if (spec.isPro) Color.White else (if (themeMode == ThemeMode.DARK) Color.White else DjObsidian),
            primaryContainer = spec.surfaceRaised,
            onPrimaryContainer = spec.accent,
            secondary = if (spec.isPro) spec.accentHover else DeckBPink,
            onSecondary = Color.White,
            secondaryContainer = spec.surfaceRaised,
            onSecondaryContainer = if (spec.isPro) spec.accent else DeckBPink,
            tertiary = if (spec.isPro) spec.accentMuted else NeonGreen,
            onTertiary = if (spec.isPro) spec.textPrimary else DjObsidian,
            background = spec.background,
            onBackground = spec.textPrimary,
            surface = spec.surface,
            onSurface = spec.textPrimary,
            surfaceVariant = spec.surfaceSunken,
            onSurfaceVariant = spec.textSecondary,
            outline = spec.divider,
            error = spec.error,
            onError = Color.White
        )

        // Android system bar integration
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalSoundSyncTheme provides spec,
            LocalLibraryDensity provides libraryDensity
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        }
    }
}

/**
 * Top-level function alias for backward compatibility.
 */
@Composable
fun SoundSyncTheme(
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    libraryDensity: ProLibraryDensity = ProLibraryDensity.COMPACT,
    content: @Composable () -> Unit,
) {
    SoundSyncTheme.invoke(themeMode = themeMode, libraryDensity = libraryDensity, content = content)
}
