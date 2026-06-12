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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meta.portal.security.ui.theme.Danger
import com.meta.portal.security.ui.theme.Ok
import com.meta.portal.security.ui.theme.PortalSecurityTheme
import com.meta.portal.security.ui.theme.Surface2
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
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
    ) { /* result handled by user re-tapping Start if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            PortalSecurityTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(
                        service = service,
                        onStart = { cfg ->
                            Config.save(this, cfg)
                            CameraAgentService.start(this)
                        },
                        onStop = { CameraAgentService.stop(this) },
                        onMotionToggle = { enabled ->
                            service?.let {
                                val cfg = Config.load(this).copy(motionEnabled = enabled)
                                Config.save(this, cfg)
                            }
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

@Composable
private fun AgentScreen(
    service: CameraAgentService?,
    onStart: (Config) -> Unit,
    onStop: () -> Unit,
    onMotionToggle: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = remember { Config.load(context) }
    var serverUrl by remember { mutableStateOf(saved.serverUrl) }
    var token by remember { mutableStateOf(saved.cameraToken) }
    var motionEnabled by remember { mutableStateOf(saved.motionEnabled) }
    var onDemand by remember { mutableStateOf(saved.onDemand) }

    val state by (service?.state?.collectAsState() ?: remember { mutableStateOf(CameraAgentService.AgentState()) })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp, start = 16.dp, end = 16.dp, bottom = 16.dp), // top 64dp: system overlay
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Portal Security", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        if (!state.running) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Signaling server (wss://…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Camera token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = onDemand,
                    onCheckedChange = { onDemand = it; if (it) motionEnabled = false },
                )
                Column {
                    Text("On-demand (camera wakes when a viewer connects)")
                    Text(
                        if (onDemand) "Camera off until someone views — best for privacy"
                        else "Camera stays on so motion alerts work while away",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = motionEnabled,
                    enabled = !onDemand,
                    onCheckedChange = { motionEnabled = it },
                )
                Text("Motion alerts" + if (onDemand) " (needs always-on)" else "")
            }
            Button(
                onClick = { onStart(Config(serverUrl, token, motionEnabled, onDemand)) },
                enabled = serverUrl.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (onDemand) "Go online (standby)" else "Start camera") }
        } else {
            PreviewSurface(service = service, capturing = state.capturing, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            StatusRow(state)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = motionEnabled, onCheckedChange = { motionEnabled = it; onMotionToggle(it) })
                Text("Motion alerts")
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop camera") }
        }
    }
}

@Composable
private fun StatusRow(state: CameraAgentService.AgentState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).then(Modifier), content = {
            Surface(color = if (state.online) Ok else Danger, shape = CircleShape, modifier = Modifier.fillMaxSize()) {}
        })
        Text(state.statusText)
        Text("· ${state.viewerCount} viewer(s)")
        if (state.lastMotionMs > 0) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastMotionMs))
            Text("· motion $t", color = Danger)
        }
    }
}

/**
 * Hosts a WebRTC SurfaceViewRenderer for the local camera preview. Initializes
 * it against the engine's EGL context once available and attaches it as a sink;
 * releases on disposal.
 */
@Composable
private fun PreviewSurface(service: CameraAgentService?, capturing: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                }
            },
            update = { renderer ->
                val egl = service?.eglContext
                if (egl != null && renderer.getTag() == null) {
                    renderer.init(egl, null)
                    renderer.setTag(true)
                    service.attachPreview(renderer)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Visible status badge: red LIVE while capturing, grey STANDBY when the
        // camera is asleep (on-demand, no viewer).
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(color = if (capturing) Danger else Color(0xFF888888), shape = CircleShape, modifier = Modifier.size(10.dp)) {}
            Text(if (capturing) "LIVE" else "STANDBY", color = Color.White)
        }
        if (!capturing) {
            Text(
                "Camera off — will wake when a viewer connects",
                color = Color(0xFFBBBBBB),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        DisposableEffect(service) {
            onDispose { service?.detachPreview() }
        }
    }
}
