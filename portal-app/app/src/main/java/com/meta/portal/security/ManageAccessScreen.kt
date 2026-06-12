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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.portal.security.ui.theme.Danger
import com.meta.portal.security.ui.theme.Ok
import com.meta.portal.security.ui.theme.Primary
import com.meta.portal.security.ui.theme.Surface as SurfaceColor
import com.meta.portal.security.ui.theme.TextColor
import com.meta.portal.security.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage who can view this camera. Enrollment is device-initiated: the owner
 * generates a single-use QR here, the viewer scans it on the same Wi-Fi, and
 * the device appears in the list — where it can be revoked.
 */
@Composable
fun ManageAccessScreen(config: Config, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { CameraApi(config.httpBaseUrl, config.cameraToken) }

    var viewers by remember { mutableStateOf<List<CameraApi.Viewer>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        error = null
        try {
            viewers = withContext(Dispatchers.IO) { api.listViewers() }
        } catch (e: Exception) {
            error = "Couldn't reach the server: ${e.message}"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 60.dp, start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MANAGE ACCESS", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        // Add a viewer: name -> QR.
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(SurfaceColor).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("ADD A VIEWER", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                            error = "Couldn't create a code: ${e.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (qr == null) "Show QR code" else "New QR code", fontWeight = FontWeight.Bold) }

            qr?.let { bmp ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
                        ) {
                            Image(bitmap = bmp, contentDescription = "Enrollment QR", modifier = Modifier.size(260.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Scan with the viewer's phone, on the same Wi-Fi. Valid ~2 minutes, one device.",
                            color = TextDim, fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // Enrolled devices.
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(SurfaceColor).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ENROLLED DEVICES", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
            }
            if (viewers.isEmpty()) {
                Text("No devices yet. Add one above.", color = TextDim, fontSize = 14.sp)
            }
            for (v in viewers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(v.name, color = TextColor, fontSize = 16.sp)
                        val seen = v.lastSeenAt?.let {
                            "last seen " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "never connected"
                        Text(
                            (if (v.revoked) "Revoked · " else "Active · ") + seen,
                            color = if (v.revoked) Danger else Ok, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { api.setRevoked(v.id, !v.revoked) }
                                    refresh()
                                } catch (e: Exception) {
                                    error = "Action failed: ${e.message}"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (v.revoked) Primary else Danger,
                        ),
                    ) { Text(if (v.revoked) "Re-enable" else "Revoke") }
                }
            }
        }

        error?.let { Text(it, color = Danger, fontSize = 13.sp) }
    }
}
