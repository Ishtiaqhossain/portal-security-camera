package com.meta.portal.security

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
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

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<CameraAgentService?>(null)

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
        requestPermissions()
        setContent {
            PortalSecurityTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppRoot(
                        service = service,
                        onArm = { cfg -> Config.save(this, cfg); CameraAgentService.start(this) },
                        onDisarm = { CameraAgentService.stop(this) },
                        onApply = { cfg ->
                            Config.save(this, cfg)
                            if (service?.state?.value?.running == true) CameraAgentService.restart(this)
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CameraAgentService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: Exception) {}
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun AppRoot(
    service: CameraAgentService?,
    onArm: (Config) -> Unit,
    onDisarm: () -> Unit,
    onApply: (Config) -> Unit,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var config by remember { mutableStateOf(Config.load(context)) }
    val state by (service?.state?.collectAsState() ?: remember { mutableStateOf(CameraAgentService.AgentState()) })

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            config = config,
            onOpenSettings = { screen = Screen.SETTINGS },
            onArm = { onArm(config) },
            onDisarm = onDisarm,
            onModeChange = { m -> val c = config.copy(mode = m); config = c; onApply(c) },
        )
        Screen.SETTINGS -> SettingsScreen(
            initial = config,
            onBack = { screen = Screen.HOME },
            onSave = { c -> config = c; onApply(c); screen = Screen.HOME },
        )
    }
}

// ---------------------------------------------------------------------------
// Home dashboard
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    state: CameraAgentService.AgentState,
    config: Config,
    onOpenSettings: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onModeChange: (CameraMode) -> Unit,
) {
    val live = state.capturing && state.viewerCount > 0
    val statusColor = when {
        !state.running -> TextDim
        !state.online -> Amber
        live -> Danger
        else -> Ok
    }
    val statusTitle = when {
        !state.running -> "Disarmed"
        !state.online -> "Connecting"
        live -> "Live"
        else -> "Protected"
    }
    val mode = if (state.running) state.mode else config.mode
    val statusSubtitle = when {
        !state.running -> "Tap Arm to start protecting"
        !state.online -> "Reaching the server…"
        live -> "${state.viewerCount} viewer${if (state.viewerCount == 1) "" else "s"} watching now"
        mode == CameraMode.DROP_IN -> "Drop In · camera wakes when a viewer connects"
        else -> "Active · streaming in the background"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 28.dp, end = 28.dp, bottom = 24.dp),
    ) {
        AppHeader(title = "PORTAL SECURITY") {
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
        }
        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Status hero
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShieldStatus(color = statusColor, modifier = Modifier.size(132.dp))
                Spacer(Modifier.height(20.dp))
                Text(statusTitle, color = statusColor, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(statusSubtitle, color = TextDim, fontSize = 15.sp)
                if (state.running && state.armedSinceMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.armedSinceMs))
                    Text("Armed since $t", color = TextDim, fontSize = 13.sp)
                }
            }

            // Controls
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(SurfaceColor).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("MODE", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    SegmentedControl(
                        labels = listOf("Drop In", "Active"),
                        selected = if (mode == CameraMode.ACTIVE) 1 else 0,
                    ) { idx -> onModeChange(if (idx == 1) CameraMode.ACTIVE else CameraMode.DROP_IN) }
                    Text(
                        if (mode == CameraMode.DROP_IN)
                            "Camera stays off until someone views — best for privacy and power."
                        else
                            "Camera streams continuously — enables motion alerts while you're away.",
                        color = TextDim, fontSize = 13.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip("Connection", if (state.online) "Online" else if (state.running) "Connecting" else "Offline",
                        if (state.online) Ok else TextDim)
                    StatChip("Viewers", state.viewerCount.toString(), if (live) Danger else TextColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val motion = if (state.lastMotionMs > 0)
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastMotionMs)) else "—"
                    StatChip("Last motion", motion, if (state.lastMotionMs > 0) Amber else TextColor)
                    StatChip("Quality", config.quality.label, TextColor)
                }

                if (!state.running) {
                    Button(
                        onClick = onArm,
                        enabled = config.isValid,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Arm", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    if (!config.isValid) {
                        Text("Set the signaling server and camera token in Settings first.",
                            color = Amber, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onDisarm,
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Disarm", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
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
    var facing by remember { mutableStateOf(initial.cameraFacing) }
    var quality by remember { mutableStateOf(initial.quality) }
    var startOnBoot by remember { mutableStateOf(initial.startOnBoot) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 60.dp, start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppHeader(title = "SETTINGS") {
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
            SettingRow("Facing") {
                SegmentedControl(
                    labels = listOf("Front", "Back"),
                    selected = if (facing == "back") 1 else 0,
                ) { idx -> facing = if (idx == 1) "back" else "front" }
            }
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
                        cameraFacing = facing, quality = quality, startOnBoot = startOnBoot,
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
private fun AppHeader(title: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
private fun RowScope.StatChip(label: String, value: String, valueColor: Color = TextColor) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
            .background(SurfaceColor).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SegmentedControl(
    labels: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (isSel) Primary else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(i) } else Modifier)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) Color.White else if (enabled) TextColor else TextDim,
                    fontSize = 15.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A drawn security shield with a checkmark, tinted to the current status color. */
@Composable
private fun ShieldStatus(color: Color, modifier: Modifier = Modifier) {
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
