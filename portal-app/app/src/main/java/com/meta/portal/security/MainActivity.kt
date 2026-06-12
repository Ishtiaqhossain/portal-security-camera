package com.meta.portal.security

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.portal.security.ui.theme.Amber
import com.meta.portal.security.ui.theme.Bg
import com.meta.portal.security.ui.theme.Danger
import com.meta.portal.security.ui.theme.Ok
import com.meta.portal.security.ui.theme.PortalSecurityTheme
import com.meta.portal.security.ui.theme.Primary
import com.meta.portal.security.ui.theme.Surface as SurfaceColor
import com.meta.portal.security.ui.theme.Surface2
import com.meta.portal.security.ui.theme.TextColor
import com.meta.portal.security.ui.theme.TextDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.util.lerp
import com.meta.portal.security.ui.theme.Motion as MotionTokens
import com.meta.portal.security.ui.theme.OnPrimary
import com.meta.portal.security.ui.theme.OutlineSoft
import com.meta.portal.security.ui.theme.Radius
import com.meta.portal.security.ui.theme.Space
import com.meta.portal.security.ui.theme.TextFaint
import com.meta.portal.security.ui.theme.Touch
import com.meta.portal.security.ui.theme.Type
import com.meta.portal.security.ui.theme.avatarColorFor
import com.meta.portal.security.ui.theme.glow
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<CameraAgentService?>(null)

    /** False on first run, until the owner creates a PIN. Set in onCreate. */
    private var pinSet by mutableStateOf(true)

    /**
     * When the owner last entered the PIN. Gated actions within [PIN_GRACE_MS]
     * skip the prompt so a flurry of actions needs only one entry. Cleared on
     * background (onStop), so returning to the app always re-authenticates.
     */
    private var lastAuthMs = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? CameraAgentService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can re-Arm if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake so the Portal's inactivity timeout / screensaver
        // doesn't background us. If something does background the app, onStop
        // fails safe to Disarmed rather than leaving a frozen, un-resumable feed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pinSet = PinManager.isPinSet(this)
        setContent {
            PortalSecurityTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    if (!pinSet) {
                        // First run: owner creates the security PIN.
                        PinSetupScreen(onDone = { pinSet = true; requestPermissions() })
                    } else {
                        // Dashboard opens freely; the PIN gates individual actions.
                        AppRoot(
                            service = service,
                            onArm = { cfg -> Config.save(this, cfg); CameraAgentService.start(this) },
                            onDisarm = { CameraAgentService.stop(this) },
                            onApply = { cfg ->
                                Config.save(this, cfg)
                                if (service?.state?.value?.running == true) CameraAgentService.restart(this)
                            },
                            graceActive = { System.currentTimeMillis() - lastAuthMs < PIN_GRACE_MS },
                            onAuthed = { lastAuthMs = System.currentTimeMillis() },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CameraAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (PinManager.isPinSet(this)) requestPermissions()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the foreground == disarming: stop the camera so we never leave
        // a frozen, un-resumable stream behind.
        if (service?.state?.value?.running == true) CameraAgentService.stop(this)
        try { unbindService(connection) } catch (_: Exception) {}
        lastAuthMs = 0L // expire the PIN grace window on background
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

/** How long a correct PIN unlocks gated actions before they prompt again. */
private const val PIN_GRACE_MS = 60_000L

private enum class Screen { HOME, SETTINGS, MANAGE }

@Composable
private fun AppRoot(
    service: CameraAgentService?,
    onArm: (Config) -> Unit,
    onDisarm: () -> Unit,
    onApply: (Config) -> Unit,
    graceActive: () -> Boolean,
    onAuthed: () -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var config by remember { mutableStateOf(Config.load(context)) }
    val state by (service?.state?.collectAsState() ?: remember { mutableStateOf(CameraAgentService.AgentState()) })

    // A sensitive action waiting on the PIN: its prompt title + what to run.
    // Within the grace window after a correct PIN, skip the prompt entirely.
    var pending by remember { mutableStateOf<GatedAction?>(null) }
    fun gate(title: String, action: () -> Unit) {
        if (graceActive()) action() else pending = GatedAction(title, action)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.HOME -> {
                // Back from the dashboard would exit (and disarm). Only gate it
                // while armed; when disarmed, let back exit normally (no prompt).
                BackHandler(enabled = state.running) {
                    gate("Enter PIN to exit") { (context as? Activity)?.finish() }
                }
                HomeScreen(
                    state = state,
                    config = config,
                    onOpenSettings = { gate("Open Settings") { screen = Screen.SETTINGS } },
                    onOpenManage = { gate("Open Viewers") { screen = Screen.MANAGE } },
                    onArm = { gate("Enter PIN to arm") { onArm(config) } },
                    onDisarm = { gate("Enter PIN to disarm") { onDisarm() } },
                    onModeChange = { m -> val c = config.copy(mode = m); config = c; onApply(c) },
                )
            }
            Screen.SETTINGS -> {
                BackHandler { screen = Screen.HOME }
                SettingsScreen(
                    initial = config,
                    onBack = { screen = Screen.HOME },
                    onSave = { c -> config = c; onApply(c); screen = Screen.HOME },
                )
            }
            Screen.MANAGE -> {
                BackHandler { screen = Screen.HOME }
                ManageAccessScreen(
                    config = config,
                    onBack = { screen = Screen.HOME },
                )
            }
        }

        pending?.let { p ->
            PinPrompt(
                title = p.title,
                onSuccess = { onAuthed(); val run = p.action; pending = null; run() },
                onCancel = { pending = null },
            )
        }
    }
}

/** A sensitive action deferred behind the PIN prompt. */
private data class GatedAction(val title: String, val action: () -> Unit)

// ---------------------------------------------------------------------------
// Home dashboard
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    state: CameraAgentService.AgentState,
    config: Config,
    onOpenSettings: () -> Unit,
    onOpenManage: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onModeChange: (CameraMode) -> Unit,
) {
    val live = state.capturing && state.viewerCount > 0
    val targetColor = when {
        !state.running -> TextDim
        !state.online -> Amber
        live -> Danger
        else -> Ok
    }
    // Cross-fade the status color so transitions (Disarmed→Live) feel authored.
    val statusColor by animateColorAsState(
        targetColor, tween(MotionTokens.emphasized), label = "statusColor",
    )
    val statusTitle = when {
        !state.running -> "Disarmed"
        !state.online -> "Connecting"
        live -> "Live"
        else -> "Protected"
    }
    val mode = if (state.running) state.mode else config.mode
    val statusSubtitle = when {
        !state.running -> "Tap Arm to start protecting your space"
        !state.online -> "Reaching the server…"
        live -> "Someone is watching right now"
        mode == CameraMode.DROP_IN -> "Camera wakes only when a viewer connects"
        else -> "Streaming quietly in the background"
    }

    // A gently-ticking clock so relative times ("2m ago") and uptime stay fresh
    // without a heavy timer. Updated on the same cadence everywhere on screen.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); now = System.currentTimeMillis() }
    }

    // Accumulate motion events observed this session into a short activity log.
    // Real data (each is a motion the service reported) — a clean seam to swap
    // in server-side history later.
    val motionLog = remember { mutableStateListOf<Long>() }
    LaunchedEffect(state.lastMotionMs) {
        val ts = state.lastMotionMs
        if (ts > 0 && (motionLog.isEmpty() || motionLog.first() != ts)) {
            motionLog.add(0, ts)
            while (motionLog.size > 3) motionLog.removeAt(motionLog.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = Space.screenTop, start = Space.screenH, end = Space.screenH, bottom = Space.xxl),
    ) {
        AppHeader(title = "Portal Security")
        Spacer(Modifier.height(Space.lg))

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Space.xxl, Alignment.CenterHorizontally),
        ) {
            // ---- Status hero (centered) + footer nav (bottom) ----------------
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        // Ambient status glow turns the empty stage into a
                        // deliberate, living surface instead of dead black.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(statusColor.copy(alpha = 0.09f), Color.Transparent),
                                center = center,
                                radius = size.minDimension * 0.75f,
                            ),
                            radius = size.minDimension * 0.75f,
                            center = center,
                        )
                    },
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatusHalo(
                        color = statusColor,
                        breathing = state.running && state.online,
                        intense = live,
                        modifier = Modifier.size(224.dp),
                    )
                    Spacer(Modifier.height(Space.lg))
                    Text(statusTitle, color = statusColor, style = Type.heroStatus)
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        statusSubtitle, color = TextDim, style = Type.body,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.lg))
                    ConnectionStatus(online = state.online, running = state.running)
                    if (state.running) {
                        Spacer(Modifier.height(Space.lg))
                        PresenceRow(viewerCount = state.viewerCount)
                        if (state.armedSinceMs > 0) {
                            Spacer(Modifier.height(Space.md))
                            Text(
                                "Armed " + uptime(state.armedSinceMs, now),
                                color = TextFaint, style = Type.caption,
                            )
                        }
                    }
                }

                // Footer nav — balances the Arm button on the opposite baseline.
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    OutlinedButton(
                        onClick = onOpenManage,
                        shape = Radius.button,
                        modifier = Modifier.weight(1f).height(Touch.min),
                    ) { Text("Viewers", style = Type.bodyStrong) }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = Radius.button,
                        modifier = Modifier.weight(1f).height(Touch.min),
                    ) { Text("Settings", style = Type.bodyStrong) }
                }
            }

            // ---- Controls ----------------------------------------------------
            Column(
                modifier = Modifier.width(470.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                ModeCard(mode = mode, quality = config.quality, motionEnabled = config.motionEnabled, onModeChange = onModeChange)
                ActivityCard(events = motionLog, now = now)
                ArmButton(
                    running = state.running,
                    canArm = config.isValid,
                    onArm = onArm,
                    onDisarm = onDisarm,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home — status hero
// ---------------------------------------------------------------------------

/**
 * The single live heartbeat of the app: the shield wrapped in a halo that
 * pings outward when the camera is LIVE, breathes calmly when armed, and sits
 * perfectly still when disarmed. Honest state, expressed as motion.
 */
@Composable
fun StatusHalo(color: Color, breathing: Boolean, intense: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "halo")
    val ping by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (intense) 1500 else 3200, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "ping",
    )
    val glow by transition.animateFloat(
        initialValue = 0.22f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            tween(if (intense) 1100 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension / 2f
            // Soft radial glow behind the shield.
            val glowAlpha = if (breathing) glow else 0.18f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = glowAlpha * 0.6f), Color.Transparent),
                    center = c, radius = maxR,
                ),
                radius = maxR, center = c,
            )
            // Static base ring.
            drawCircle(color.copy(alpha = 0.14f), radius = maxR * 0.64f, center = c, style = Stroke(2.dp.toPx()))
            // Expanding ping — the live "someone's here" pulse.
            if (breathing) {
                val r = lerp(maxR * 0.64f, maxR * 0.98f, ping)
                val a = (1f - ping) * (if (intense) 0.5f else 0.26f)
                drawCircle(color.copy(alpha = a), radius = r, center = c, style = Stroke((if (intense) 3 else 2).dp.toPx()))
            }
        }
        ShieldStatus(color = color, modifier = Modifier.size(maxShieldSize))
    }
}

private val maxShieldSize = 104.dp

@Composable
private fun ConnectionStatus(online: Boolean, running: Boolean) {
    val (label, dot) = when {
        online -> "Connected to server" to Ok
        running -> "Connecting…" to Amber
        else -> "Offline" to TextFaint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(label, color = TextDim, style = Type.caption)
    }
}

/**
 * Who can see the camera right now. With only a viewer count available we show
 * honest presence avatars; when names are plumbed through AgentState this is
 * the one place to swap circles for initials.
 */
@Composable
private fun PresenceRow(viewerCount: Int) {
    if (viewerCount <= 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(TextFaint))
            Text("No one is viewing", color = TextFaint, style = Type.caption)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            repeat(minOf(viewerCount, 4)) { i -> ViewerAvatar(index = i) }
        }
        val n = viewerCount
        Text(
            (if (n > 4) "+${n - 4} · " else "") + "$n watching now",
            color = Danger, style = Type.bodyStrong,
        )
    }
}

@Composable
private fun ViewerAvatar(index: Int) {
    val tint = avatarColorFor("viewer-$index")
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Bg) // ring gap so overlapped avatars read as separate
            .padding(2.dp)
            .clip(CircleShape)
            .background(tint.glow(0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width; val h = size.height
            // head
            drawCircle(tint, radius = w * 0.20f, center = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.34f))
            // shoulders
            val body = Path().apply {
                addArc(
                    androidx.compose.ui.geometry.Rect(
                        left = w * 0.16f, top = h * 0.55f, right = w * 0.84f, bottom = h * 1.25f,
                    ),
                    180f, 180f,
                )
            }
            drawPath(body, tint)
        }
    }
}

@Composable
private fun ModeCard(
    mode: CameraMode,
    quality: VideoQuality,
    motionEnabled: Boolean,
    onModeChange: (CameraMode) -> Unit,
) {
    Card {
        Text("MODE", color = TextDim, style = Type.label)
        SegmentedControl(
            labels = listOf("Drop In", "Active"),
            selected = if (mode == CameraMode.ACTIVE) 1 else 0,
        ) { idx -> onModeChange(if (idx == 1) CameraMode.ACTIVE else CameraMode.DROP_IN) }
        Text(
            if (mode == CameraMode.DROP_IN)
                "Camera stays off until someone views — best for privacy and power."
            else
                "Camera streams continuously — enables motion alerts while you're away.",
            color = TextDim, style = Type.caption,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            MetaPill("${quality.label}")
            MetaPill(if (mode == CameraMode.ACTIVE && motionEnabled) "Alerts on" else "Alerts off")
        }
    }
}

/** A small, quiet info pill for at-a-glance config facts. */
@Composable
private fun MetaPill(text: String) {
    Box(
        modifier = Modifier.clip(Radius.pill).background(Surface2).padding(horizontal = Space.md, vertical = 6.dp),
    ) { Text(text, color = TextDim, style = Type.caption) }
}

@Composable
private fun ColumnScope.ActivityCard(events: List<Long>, now: Long) {
    Card(Modifier.weight(1f)) {
        Text("RECENT ACTIVITY", color = TextDim, style = Type.label)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (events.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Canvas(Modifier.size(60.dp)) {
                        val r = size.minDimension / 2f
                        drawCircle(OutlineSoft, radius = r * 0.95f, style = Stroke(1.5.dp.toPx()))
                        drawCircle(OutlineSoft, radius = r * 0.58f, style = Stroke(1.5.dp.toPx()))
                        drawCircle(TextFaint, radius = r * 0.12f)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("No motion detected", color = TextDim, style = Type.body)
                        Text("You're all clear", color = TextFaint, style = Type.caption)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    events.forEachIndexed { i, ts ->
                        val fresh = now - ts < 30_000
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (fresh) Amber else TextFaint))
                            Text("Motion detected", color = if (fresh) TextColor else TextDim, style = Type.body, modifier = Modifier.weight(1f))
                            Text(relTime(ts, now), color = TextFaint, style = Type.caption)
                        }
                        if (i < events.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineSoft))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one consequential action. Arm is a confident primary; Disarm asks for a
 * second tap so the camera never goes dark by accident.
 */
@Composable
private fun ArmButton(running: Boolean, canArm: Boolean, onArm: () -> Unit, onDisarm: () -> Unit) {
    if (!running) {
        Button(
            onClick = onArm,
            enabled = canArm,
            shape = Radius.button,
            modifier = Modifier.fillMaxWidth().height(Touch.action),
        ) { Text("Arm", style = Type.titleSmall) }
        if (!canArm) {
            Spacer(Modifier.height(Space.sm))
            Text(
                "Set the signaling server and camera token in Settings first.",
                color = Amber, style = Type.caption,
            )
        }
        return
    }

    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    Button(
        onClick = { if (confirm) { confirm = false; onDisarm() } else confirm = true },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (confirm) Danger else Surface2,
            contentColor = if (confirm) OnPrimary else Danger,
        ),
        shape = Radius.button,
        modifier = Modifier.fillMaxWidth().height(Touch.action),
    ) { Text(if (confirm) "Tap again to disarm" else "Disarm", style = Type.titleSmall) }
}

/** Shared rounded surface card with consistent padding & rhythm. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(Radius.card).background(SurfaceColor).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        content = content,
    )
}

private fun relTime(ts: Long, now: Long): String {
    val d = (now - ts).coerceAtLeast(0)
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000}m ago"
        d < 86_400_000 -> "${d / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}

private fun uptime(since: Long, now: Long): String {
    val d = (now - since).coerceAtLeast(0)
    val mins = d / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "for ${mins}m"
        else -> "for ${mins / 60}h ${mins % 60}m"
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun SettingsScreen(
    initial: Config,
    onBack: () -> Unit,
    onSave: (Config) -> Unit,
) {
    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var token by remember { mutableStateOf(initial.cameraToken) }
    var mode by remember { mutableStateOf(initial.mode) }
    var motion by remember { mutableStateOf(initial.motionEnabled) }
    var quality by remember { mutableStateOf(initial.quality) }
    var startOnBoot by remember { mutableStateOf(initial.startOnBoot) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 60.dp, start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppHeader(title = "Settings") {
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        SectionCard("Connection") {
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("Signaling server (wss://…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Camera token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Mode") {
            SegmentedControl(
                labels = listOf("Drop In", "Active"),
                selected = if (mode == CameraMode.ACTIVE) 1 else 0,
            ) { idx -> mode = if (idx == 1) CameraMode.ACTIVE else CameraMode.DROP_IN }
            Text(
                if (mode == CameraMode.DROP_IN)
                    "Drop In — camera activates only when a viewer connects."
                else
                    "Active — camera streams continuously in the background.",
                color = TextDim, fontSize = 13.sp,
            )
        }

        SectionCard("Camera") {
            SettingRow("Quality") {
                SegmentedControl(
                    labels = VideoQuality.entries.map { it.label },
                    selected = VideoQuality.entries.indexOf(quality),
                ) { idx -> quality = VideoQuality.entries[idx] }
            }
        }

        SectionCard("Alerts") {
            ToggleRow(
                title = "Motion alerts",
                subtitle = if (mode == CameraMode.ACTIVE) "Notify viewers when motion is detected."
                else "Requires Active mode (camera must stay on to detect motion).",
                checked = motion && mode == CameraMode.ACTIVE,
                enabled = mode == CameraMode.ACTIVE,
                onCheckedChange = { motion = it },
            )
        }

        SectionCard("Startup") {
            ToggleRow(
                title = "Start on boot",
                subtitle = "Re-arm automatically in the background after the device restarts.",
                checked = startOnBoot,
                enabled = true,
                onCheckedChange = { startOnBoot = it },
            )
        }

        Button(
            onClick = {
                onSave(
                    initial.copy(
                        serverUrl = serverUrl, cameraToken = token, mode = mode,
                        motionEnabled = motion && mode == CameraMode.ACTIVE,
                        quality = quality, startOnBoot = startOnBoot,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

        Text("Portal Security · v0.2.0", color = TextDim, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------
// Reusable pieces
// ---------------------------------------------------------------------------

@Composable
private fun AppHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShieldStatus(color = Primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(Space.md))
        Text(title, color = TextColor, style = Type.title)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title.uppercase(), color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = TextColor, fontSize = 15.sp)
        control()
    }
}

@Composable
private fun ToggleRow(
    title: String, subtitle: String, checked: Boolean, enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (enabled) TextColor else TextDim, fontSize = 16.sp)
            Text(subtitle, color = TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Primary),
        )
    }
}

@Composable
private fun SegmentedControl(
    labels: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(Radius.control)
            .background(Surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier.weight(1f).clip(Radius.controlInner)
                    .background(if (isSel) Primary else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(i) } else Modifier)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) OnPrimary else if (enabled) TextColor else TextFaint,
                    style = if (isSel) Type.bodyStrong else Type.body,
                )
            }
        }
    }
}

/** A drawn security shield with a checkmark, tinted to the current status color. */
@Composable
fun ShieldStatus(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.02f)
            lineTo(w * 0.90f, h * 0.16f)
            lineTo(w * 0.90f, h * 0.50f)
            cubicTo(w * 0.90f, h * 0.78f, w * 0.72f, h * 0.93f, w * 0.5f, h * 0.99f)
            cubicTo(w * 0.28f, h * 0.93f, w * 0.10f, h * 0.78f, w * 0.10f, h * 0.50f)
            lineTo(w * 0.10f, h * 0.16f)
            close()
        }
        drawPath(shield, color.copy(alpha = 0.16f))
        drawPath(shield, color, style = Stroke(width = w * 0.035f))
        val check = Path().apply {
            moveTo(w * 0.34f, h * 0.50f)
            lineTo(w * 0.46f, h * 0.63f)
            lineTo(w * 0.68f, h * 0.36f)
        }
        drawPath(check, color, style = Stroke(width = w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
