package com.meta.portal.security.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Spacing, shape & motion tokens.
//
// An 8dp grid. Use these instead of inline `.dp` so rhythm stays consistent
// and a future density change is one edit, not a hundred.
// ---------------------------------------------------------------------------

/** Spacing scale (8dp grid, with a couple of in-between steps). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Screen edge insets. Top clears the Portal's 64dp system overlay. */
    val screenH = 28.dp
    val screenTop = 56.dp
    val screenBottom = 24.dp
}

/** Corner radii. */
object Radius {
    val card = RoundedCornerShape(20.dp)
    val chip = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(12.dp)
    val controlInner = RoundedCornerShape(9.dp)
    val button = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(999.dp)
}

/** Minimum touch target — Portal guidance is ≥ 52dp. */
object Touch {
    val min = 52.dp
    val action = 58.dp // primary Arm/Disarm
}

/** Animation timings. One vocabulary so every transition feels authored. */
object Motion {
    const val fast = 150
    const val standard = 280
    const val emphasized = 420
    const val pulse = 1400 // the single live heartbeat (matches web pulse)
}
