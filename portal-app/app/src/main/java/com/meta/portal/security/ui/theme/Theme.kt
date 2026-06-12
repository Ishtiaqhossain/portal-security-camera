package com.meta.portal.security.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Portal palette. Dark is mandatory (white system overlay would vanish on light).
val Bg = Color(0xFF1A1A1A)
val Surface = Color(0xFF2B2B2B)
val Surface2 = Color(0xFF353535)
val Primary = Color(0xFF0866FF)
val OnPrimary = Color(0xFFF0F0F0)
val TextColor = Color(0xFFDADADA)
val TextDim = Color(0xFF8A8A8A)
val Danger = Color(0xFFE5484D)
val Ok = Color(0xFF30A46C)
val Amber = Color(0xFFE0A23B)
val Outline = Color(0xFF3A3A3A)

private val PortalColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Bg,
    onBackground = TextColor,
    surface = Surface,
    onSurface = TextColor,
    error = Danger,
)

// Inter is the Portal type face; bundle Inter*.ttf in res/font and swap the
// default FontFamily here. Sizes follow Portal guidance: body 18sp, min 14sp.
private val PortalType = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun PortalSecurityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PortalColors,
        typography = PortalType,
        content = content,
    )
}
