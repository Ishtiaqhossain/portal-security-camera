package com.meta.portal.security.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.meta.portal.security.R

// ---------------------------------------------------------------------------
// Type — Inter, bundled in res/font (OFL, see licenses/Inter-OFL.txt).
//
// `Inter` is the text face; `InterDisplay` is the optical-size variant used for
// large display copy (the status hero, lock title) where the tighter spacing
// reads as more confident. Sizes honor Portal guidance: glanceable from across
// a room, body ≥ 16sp, min 13sp.
// ---------------------------------------------------------------------------

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/**
 * Named semantic text styles. Prefer these over inline `fontSize =` so the
 * scale stays consistent — they read like the design spec, not magic numbers.
 */
object Type {
    /** The one big status word on Home ("Live", "Protected"). */
    val heroStatus = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, letterSpacing = (-0.5).sp,
    )
    val display = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, letterSpacing = (-0.2).sp,
    )
    val title = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
    )
    val titleSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
    )
    val body = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp,
    )
    val bodyStrong = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp,
    )
    val caption = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp,
    )
    /** Uppercase section labels — tracked out for that "system panel" feel. */
    val label = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, letterSpacing = 1.2.sp,
    )
    /** Large numerals / value readouts (viewer count, etc.). */
    val statValue = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp,
    )
}

/** Material3 Typography so MaterialTheme.typography defaults to Inter too. */
val PortalTypography = Typography(
    headlineSmall = Type.display,
    titleLarge = Type.title,
    titleMedium = Type.titleSmall,
    bodyLarge = Type.body,
    bodyMedium = Type.caption,
    labelLarge = Type.bodyStrong,
    labelMedium = Type.label,
)
