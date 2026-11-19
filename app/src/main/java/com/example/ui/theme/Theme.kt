package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.domain.model.AccentPreset
import com.example.domain.model.ThemeMode

@Composable
fun KoplingfyTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentPreset: AccentPreset = AccentPreset.AMBER_GOLD,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val accent = Color(accentPreset.hex)
    val secondaryAccent = when (accentPreset) {
        AccentPreset.AMBER_GOLD -> ElectricCoral
        AccentPreset.ELECTRIC_CORAL -> AmberGold
        AccentPreset.CYBER_TEAL -> RoyalViolet
        AccentPreset.ROYAL_VIOLET -> CyberTeal
        AccentPreset.EMERALD_STUDIO -> AmberGold
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            when (themeMode) {
                ThemeMode.LIGHT -> dynamicLightColorScheme(context)
                ThemeMode.AMOLED -> dynamicDarkColorScheme(context).copy(
                    background = AmoledBackground,
                    surface = AmoledSurface,
                    surfaceContainer = AmoledSurfaceHigh
                )
                ThemeMode.DARK -> dynamicDarkColorScheme(context)
            }
        }
        themeMode == ThemeMode.LIGHT -> lightColorScheme(
            primary = accent,
            onPrimary = Color(0xFF090A10),
            primaryContainer = accent.copy(alpha = 0.16f),
            onPrimaryContainer = Color(0xFF1A1407),
            secondary = secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = secondaryAccent.copy(alpha = 0.14f),
            onSecondaryContainer = StudioLightOnSurface,
            background = StudioLightBackground,
            onBackground = StudioLightOnSurface,
            surface = StudioLightSurface,
            onSurface = StudioLightOnSurface,
            surfaceVariant = StudioLightSurfaceHigh,
            onSurfaceVariant = Color(0xFF565B70),
            surfaceContainer = StudioLightSurface,
            surfaceContainerHigh = StudioLightSurfaceHigh,
            outline = Color(0xFFD5D9E6)
        )
        themeMode == ThemeMode.AMOLED -> darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF090A10),
            primaryContainer = accent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = secondaryAccent.copy(alpha = 0.2f),
            onSecondaryContainer = Color.White,
            background = AmoledBackground,
            onBackground = Color(0xFFF6F7FB),
            surface = AmoledSurface,
            onSurface = Color(0xFFF6F7FB),
            surfaceVariant = AmoledSurfaceHigh,
            onSurfaceVariant = Color(0xFF9EA4B8),
            surfaceContainer = AmoledSurface,
            surfaceContainerHigh = AmoledSurfaceHigh,
            outline = Color(0xFF232635)
        )
        else -> darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF090A10),
            primaryContainer = accent.copy(alpha = 0.2f),
            onPrimaryContainer = Color.White,
            secondary = secondaryAccent,
            onSecondary = Color.White,
            secondaryContainer = secondaryAccent.copy(alpha = 0.2f),
            onSecondaryContainer = Color.White,
            background = ObsidianBackground,
            onBackground = Color(0xFFF5F6FA),
            surface = ObsidianSurface,
            onSurface = Color(0xFFF5F6FA),
            surfaceVariant = ObsidianSurfaceHigh,
            onSurfaceVariant = Color(0xFFA3A9BE),
            surfaceContainer = ObsidianSurface,
            surfaceContainerHigh = ObsidianSurfaceHigh,
            surfaceContainerHighest = ObsidianSurfaceHighest,
            outline = ObsidianOutline
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KoplingfyTypography,
        content = content
    )
}
