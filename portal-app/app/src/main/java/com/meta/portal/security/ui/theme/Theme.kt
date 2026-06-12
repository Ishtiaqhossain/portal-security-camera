package com.meta.portal.security.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Color tokens live in Color.kt, type in Type.kt, spacing/shape/motion in
// Dimens.kt. This file just assembles them into the MaterialTheme.

private val PortalColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Bg,
    onBackground = TextColor,
    surface = Surface,
    onSurface = TextColor,
    surfaceVariant = Surface2,
    outline = Outline,
    error = Danger,
)

@Composable
fun PortalSecurityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PortalColors,
        typography = PortalTypography,
    ) {
        // Make Inter + default text color the baseline for every Text, so
        // bare `Text(..., fontSize = …)` calls inherit the type face and don't
        // fall back to the system sans.
        val base = LocalTextStyle.current.merge(Type.body.copy(color = TextColor))
        CompositionLocalProvider(LocalTextStyle provides base, content = content)
    }
}
