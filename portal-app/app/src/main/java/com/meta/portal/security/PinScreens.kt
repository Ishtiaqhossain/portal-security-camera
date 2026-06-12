package com.meta.portal.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meta.portal.security.ui.theme.Bg
import com.meta.portal.security.ui.theme.Danger
import com.meta.portal.security.ui.theme.Primary
import com.meta.portal.security.ui.theme.Space
import com.meta.portal.security.ui.theme.Surface2
import com.meta.portal.security.ui.theme.TextColor
import com.meta.portal.security.ui.theme.TextDim
import com.meta.portal.security.ui.theme.TextFaint
import com.meta.portal.security.ui.theme.Type
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// PIN gate — first-run setup + unlock-on-every-foreground.
//
// The owner sets a 4-digit PIN once; it's the only security gate. Both screens
// share the dashboard's two-column language: identity (halo + title + dots) on
// the left, keypad on the right.
// ---------------------------------------------------------------------------

/** First-run: create a PIN, then confirm it. */
@Composable
fun PinSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var first by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    // Act once the 4th digit has painted: the last dot fills, then we react.
    LaunchedEffect(entered) {
        if (entered.length < PinManager.LENGTH) return@LaunchedEffect
        delay(140)
        when {
            !confirming -> { first = entered; entered = ""; confirming = true }
            entered == first -> { PinManager.setPin(ctx, first); onDone() }
            else -> { mismatch = true; first = ""; entered = ""; confirming = false }
        }
    }

    fun onDigit(d: Int) {
        if (entered.length >= PinManager.LENGTH) return
        mismatch = false
        entered += d
    }

    PinScaffold(
        accent = Primary,
        title = if (confirming) "Confirm your PIN" else "Create a PIN",
        subtitle = when {
            mismatch -> "PINs didn't match — let's try again"
            confirming -> "Enter the same 4 digits once more"
            else -> "Only someone with this PIN can arm, disarm, or change who's watching"
        },
        subtitleColor = if (mismatch) Danger else TextDim,
        filled = entered.length,
        error = mismatch,
        onDigit = ::onDigit,
        onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
    )
}

/**
 * A modal PIN gate for a single sensitive action (arm, disarm, open Settings/
 * Viewers, exit). Shown over the current screen; [onSuccess] runs on the right
 * PIN, [onCancel] backs out. Lockout after repeated failures.
 */
@Composable
fun PinPrompt(title: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableStateOf(PinManager.lockoutRemainingMs(ctx)) }

    BackHandler(onBack = onCancel)

    // Tick down the lockout so the keypad re-enables on its own.
    LaunchedEffect(lockoutMs > 0) {
        while (lockoutMs > 0) { delay(300); lockoutMs = PinManager.lockoutRemainingMs(ctx) }
    }

    // Verify once the 4th digit has painted: the last dot fills, then we check.
    LaunchedEffect(entered) {
        if (entered.length < PinManager.LENGTH) return@LaunchedEffect
        delay(140)
        if (PinManager.verify(ctx, entered)) {
            onSuccess()
        } else {
            wrong = true; entered = ""; lockoutMs = PinManager.lockoutRemainingMs(ctx)
        }
    }

    val locked = lockoutMs > 0
    // Near-opaque scrim so the dashboard behind doesn't leak during PIN entry.
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.94f))) {
        PinScaffold(
            accent = if (locked || wrong) Danger else Primary,
            title = title,
            subtitle = when {
                locked -> "Too many attempts — try again in ${(lockoutMs / 1000) + 1}s"
                wrong -> "Wrong PIN — try again"
                else -> "Enter your PIN to continue"
            },
            subtitleColor = if (locked || wrong) Danger else TextDim,
            filled = entered.length,
            error = wrong || locked,
            enabled = !locked,
            onCancel = onCancel,
            onDigit = { d -> if (!locked && entered.length < PinManager.LENGTH) { wrong = false; entered += d } },
            onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
        )
    }
}

// ---------------------------------------------------------------------------
// Shared layout + keypad
// ---------------------------------------------------------------------------

@Composable
private fun PinScaffold(
    accent: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    filled: Int,
    error: Boolean,
    enabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = Space.screenTop, start = Space.screenH, end = Space.screenH, bottom = Space.screenBottom),
            horizontalArrangement = Arrangement.spacedBy(Space.xxl, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusHalo(color = accent, breathing = true, intense = error, modifier = Modifier.size(168.dp))
                Spacer(Modifier.height(Space.xl))
                Text(title, color = TextColor, style = Type.display, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.sm))
                Text(subtitle, color = subtitleColor, style = Type.body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.xl))
                PinDots(filled = filled, accent = accent, error = error)
            }

            PinPad(enabled = enabled, onDigit = onDigit, onBackspace = onBackspace)
        }

        if (onCancel != null) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = Space.screenTop, start = Space.screenH),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun PinDots(filled: Int, accent: Color, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        repeat(PinManager.LENGTH) { i ->
            val on = i < filled
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> Danger
                            on -> accent
                            else -> Surface2
                        }
                    ),
            )
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, onDigit: (Int) -> Unit, onBackspace: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                row.forEach { d -> DigitKey(d, enabled, onDigit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            Spacer(Modifier.size(78.dp))
            DigitKey(0, enabled, onDigit)
            BackspaceKey(enabled, onBackspace)
        }
    }
}

@Composable
private fun DigitKey(digit: Int, enabled: Boolean, onClick: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(Surface2)
            .then(if (enabled) Modifier.clickable { onClick(digit) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), color = if (enabled) TextColor else TextFaint, style = Type.display)
    }
}

@Composable
private fun BackspaceKey(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // A drawn backspace glyph (Inter has no reliable ⌫), tinted dim.
        Canvas(Modifier.size(30.dp)) {
            val w = size.width; val h = size.height
            val tip = w * 0.30f
            val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(tip, h * 0.5f)
                lineTo(w * 0.52f, h * 0.18f)
                lineTo(w * 0.95f, h * 0.18f)
                lineTo(w * 0.95f, h * 0.82f)
                lineTo(w * 0.52f, h * 0.82f)
                close()
            }
            drawPath(path, TextDim, style = stroke)
            // the little × inside
            drawLine(TextDim, androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.62f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            drawLine(TextDim, androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.62f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        }
    }
}
