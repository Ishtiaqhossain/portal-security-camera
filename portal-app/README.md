# Portal app — security camera agent

The Android app that runs on the Portal device. It's a background security
camera: it connects to the signaling server, answers WebRTC offers from browser
viewers, streams camera + mic, plays viewer talk-back audio, and runs motion
detection. The device shows a **professional status dashboard — not the live
feed** (the feed is for remote viewers only).

## Modes

- **Drop In** — the camera stays off and only captures while a viewer is
  connected (wakes on connect, sleeps on disconnect). Best for privacy/power.
- **Active** — the camera streams continuously in the background, which is what
  enables motion alerts while you're away.

## Screens

- **Dashboard** — shield status (Disarmed → Protected → Live), mode selector,
  live stats (connection, viewers, last motion, quality), and Arm/Disarm.
- **Settings** — connection (server URL + token), mode, camera facing
  (front/back), video quality (480p/720p/1080p), motion alerts, start-on-boot.

## Architecture

```
MainActivity (Compose: Dashboard + Settings, no on-device feed)
   └─ binds to ─▶ CameraAgentService  (foreground service; runs in background)
                     ├─ SignalingClient   (OkHttp WebSocket ⇄ signaling-server)
                     └─ WebRtcEngine       (on-demand or continuous capture; one PeerConnection per viewer)
                           ├─ MotionDetector  (VideoSink, luma frame-diff; Active mode)
                           └─ remote audio    (viewer talk-back, auto-played)
BootReceiver ─▶ re-arms the service after reboot when "Start on boot" is set.
```

WebRTC roles match the web viewer: the **viewer offers**, the **camera answers**.
The camera sends video (sendonly) + mic (sendrecv) and receives the viewer's
talk-back audio.

## Prerequisites

- **JDK 17–21.** Android Studio bundles a compatible JBR. The build was verified
  with the Android Studio JBR (JDK 21). Plain JDK 24 is too new for AGP 8.5 /
  Gradle 8.9 — point `JAVA_HOME` at a 17–21 JDK.
- Android SDK with a platform installed (see note on `compileSdk` below).
- A Portal device in Developer Mode, reachable over ADB (USB-C), **or** `hzdb`.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17–21
./gradlew :app:assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

### Note on `compileSdk`

Per Portal guidance the app uses `minSdk 28` / `targetSdk 29`. `compileSdk` only
affects which APIs are visible at compile time, not runtime behavior. This repo
sets `compileSdk = 36` + `buildToolsVersion = "35.0.0"` because that's what was
installed locally, with `android.suppressUnsupportedCompileSdk=36` in
`gradle.properties`. If you prefer the canonical setup, install platform
`android-34` (`sdkmanager "platforms;android-34" "build-tools;34.0.0"`), set
`compileSdk = 34`, and drop the suppress flag.

## Deploy & run

Plain ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.meta.portal.security/.MainActivity
```

With hzdb (after `hzdb mcp install claude-code` and a session restart, the MCP
tools also work; from the shell):
```bash
npx -y @meta-quest/hzdb app install app/build/outputs/apk/debug/app-debug.apk
npx -y @meta-quest/hzdb app launch com.meta.portal.security
```

## Configure on device

1. Launch the app and grant camera + microphone permissions.
2. Open **Settings** → enter the **signaling server URL** (e.g. `wss://your-server`)
   and the **camera token** (the server's `CAMERA_TOKEN`); pick a **mode**,
   camera facing, and quality; **Save**.
3. On the dashboard tap **Arm**. The shield turns green (**Protected**); it goes
   red (**Live**) when a viewer connects. Open the web viewer to watch.

For local testing over USB without a public server, bridge the device to a
laptop server with `adb reverse tcp:8080 tcp:8080` and use `ws://localhost:8080`.

## Files

| File | Role |
|------|------|
| `MainActivity.kt` | Compose UI: status dashboard + Settings (no on-device feed). |
| `CameraAgentService.kt` | Foreground service; wires signaling ⇄ engine; arm/disarm/restart. |
| `WebRtcEngine.kt` | PeerConnectionFactory; on-demand or continuous capture; per-viewer peers. |
| `SignalingClient.kt` | WebSocket client speaking the server's JSON protocol. |
| `MotionDetector.kt` | Luma frame-differencing on the live video (Active mode). |
| `BootReceiver.kt` | Re-arms the agent after reboot when "Start on boot" is set. |
| `Config.kt` | Persisted settings (server, token, mode, facing, quality, motion, boot). |
