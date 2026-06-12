package com.meta.portal.security

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meta.portal.security.ui.theme.Danger
import com.meta.portal.security.ui.theme.Ok
import com.meta.portal.security.ui.theme.OutlineSoft
import com.meta.portal.security.ui.theme.Primary
import com.meta.portal.security.ui.theme.Radius
import com.meta.portal.security.ui.theme.Space
import com.meta.portal.security.ui.theme.Surface as SurfaceColor
import com.meta.portal.security.ui.theme.TextColor
import com.meta.portal.security.ui.theme.TextDim
import com.meta.portal.security.ui.theme.TextFaint
import com.meta.portal.security.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage who can view this camera. Enrollment is device-initiated: the owner
 * generates a single-use QR here, the viewer scans it on the same Wi-Fi, and
 * the device appears in the list — where it can be revoked. Two-column layout:
 * add a viewer on the left, the enrolled list on the right.
 */
@Composable
fun ManageAccessScreen(config: Config, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Reload from prefs: the agent writes cameraId after it provisions on first
    // Arm, which can happen after this screen's parent loaded its Config. Using
    // the stale (blank) cameraId would leave the signed enroll request
    // unauthenticated (401) and QR creation would fail.
    val api = remember {
        val current = Config.load(context)
        CameraApi(current.httpBaseUrl, current.cameraToken, current.cameraId, CameraIdentity())
    }

    var viewers by remember { mutableStateOf<List<CameraApi.Viewer>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        error = null
        try {
            viewers = withContext(Dispatchers.IO) { api.listViewers() }
        } catch (e: Exception) {
            error = "Can't reach the camera server. Check your connection."
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Space.screenTop, start = Space.screenH, end = Space.screenH, bottom = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
            AppHeader(title = "Viewers") {
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Spacer(Modifier.height(Space.xl))

            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                // ---- Add a viewer --------------------------------------------
                AccessCard(modifier = Modifier.weight(1f)) {
                    Text("ADD A VIEWER", color = TextDim, style = Type.label)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Device name (e.g. \"Mom's iPhone\")") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                error = null
                                try {
                                    val url = withContext(Dispatchers.IO) { api.startEnroll(name.ifBlank { "New device" }) }
                                    qr = QrGen.encode(url, 640).asImageBitmap()
                                } catch (e: Exception) {
                                    error = "Couldn't create a code. Try again."
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        shape = Radius.button,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (qr == null) "Show QR code" else "New QR code", style = Type.bodyStrong) }

                    qr?.let { bmp ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.clip(Radius.control).background(Color.White).padding(Space.md),
                            ) {
                                Image(bitmap = bmp, contentDescription = "Enrollment QR", modifier = Modifier.size(240.dp))
                            }
                            Spacer(Modifier.height(Space.md))
                            Text(
                                "Scan on the same Wi-Fi as the camera (a one-time security check). " +
                                    "Grants that one device ongoing access — revoke it anytime below. Code expires in ~2 min.",
                                color = TextDim, style = Type.caption,
                            )
                        }
                    }
                }

                // ---- Enrolled devices ----------------------------------------
                AccessCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ENROLLED DEVICES", color = TextDim, style = Type.label)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
                    }
                    if (viewers.isEmpty()) {
                        Text("No devices yet. Add one on the left.", color = TextFaint, style = Type.body)
                    }
                    viewers.forEachIndexed { i, v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(v.name, color = TextColor, style = Type.bodyStrong)
                                val seen = v.lastSeenAt?.let {
                                    "last seen " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                                } ?: "never connected"
                                Text(
                                    (if (v.revoked) "Revoked · " else "Active · ") + seen,
                                    color = if (v.revoked) Danger else Ok, style = Type.caption,
                                )
                            }
                            Spacer(Modifier.width(Space.md))
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { api.setRevoked(v.id, !v.revoked) }
                                            refresh()
                                        } catch (e: Exception) {
                                            error = "That didn't work. Try again."
                                        }
                                    }
                                },
                                shape = Radius.button,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (v.revoked) Primary else Danger,
                                ),
                            ) { Text(if (v.revoked) "Re-enable" else "Revoke") }
                        }
                        if (i < viewers.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineSoft))
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(Space.lg))
                Text(it, color = Danger, style = Type.caption)
            }
        }
    }
}

/** A rounded surface card used by the two columns. */
@Composable
private fun AccessCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.clip(Radius.card).background(SurfaceColor).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        content = content,
    )
}
