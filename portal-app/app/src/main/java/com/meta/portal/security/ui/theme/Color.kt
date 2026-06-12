package com.meta.portal.security.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Portal Security palette.
//
// Dark is mandatory — the Portal renders a white system overlay in the top
// 64dp, so a light app would vanish behind it. The palette is shared with the
// web viewer (web-client/styles.css) so the two surfaces read as one product;
// Android adds a depth ramp and status-glow tints the web can't render.
// ---------------------------------------------------------------------------

// --- Neutral surface ramp (sunken → raised) --------------------------------
val BgSunken = Color(0xFF121212)   // insets, timeline tracks — recedes
val Bg = Color(0xFF1A1A1A)         // app background (matches web --bg)
val Surface = Color(0xFF242424)    // cards / panels
val Surface2 = Color(0xFF303030)   // segmented controls, inputs
val SurfaceRaised = Color(0xFF383838) // hovered / selected fills

// --- Brand -----------------------------------------------------------------
val Primary = Color(0xFF0866FF)        // Meta blue (matches web --primary)
val PrimaryPressed = Color(0xFF2978FF) // lighter, for pressed/hover (web hover)
val OnPrimary = Color(0xFFFFFFFF)

// --- Text ------------------------------------------------------------------
val TextColor = Color(0xFFEDEDED)  // primary text (slightly brighter than before)
val TextDim = Color(0xFF9A9A9A)    // secondary text / labels
val TextFaint = Color(0xFF6A6A6A)  // tertiary — placeholders, disabled

// --- Status (truthful, not reassuring) -------------------------------------
val Danger = Color(0xFFE5484D)  // live / errors / revoke (matches web --danger)
val Ok = Color(0xFF30A46C)      // armed & protected (matches web --ok)
val Amber = Color(0xFFE0A23B)   // connecting / attention

// --- Lines & glows ---------------------------------------------------------
val Outline = Color(0xFF333333)     // card / control borders
val OutlineSoft = Color(0xFF272727) // hairline dividers

/** Translucent status fills for halos, glows, and tinted chips. */
fun Color.glow(alpha: Float = 0.16f): Color = copy(alpha = alpha)

/** A small, friendly palette for named-viewer avatars (stable per name). */
val AvatarPalette = listOf(
    Color(0xFF0866FF), // blue
    Color(0xFF30A46C), // green
    Color(0xFFE0A23B), // amber
    Color(0xFF8E5BEF), // violet
    Color(0xFF2BB3C0), // teal
    Color(0xFFE5677D), // rose
)

/** Pick a deterministic avatar color from a viewer name. */
fun avatarColorFor(name: String): Color =
    AvatarPalette[(name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % AvatarPalette.size]
