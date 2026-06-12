# Portal app — camera agent

The Android app that runs on the Portal device. It captures the camera +
microphone, connects to the signaling server, answers WebRTC offers from
browser viewers, plays back viewer talk-back audio, runs motion detection, and
shows a persistent **LIVE** indicator while active.

## Architecture

```
MainActivity (Compose UI)
   └─ binds to ─▶ CameraAgentService  (foreground service, always-on)
                     ├─ SignalingClient   (OkHttp WebSocket ⇄ signaling-server)
                     └─ WebRtcEngine       (camera+mic capture, one PeerConnection per viewer)
                           ├─ MotionDetector  (VideoSink, luma frame-diff)
                           └─ remote audio    (viewer talk-back, auto-played)
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

1. Launch the app. Grant camera, microphone, and notification permissions.
2. Enter the **signaling server URL** (e.g. `wss://your-server`) and the
   **camera token** (the server's `CAMERA_TOKEN`).
3. Tap **Start camera**. The foreground notification and on-screen LIVE badge
   confirm it's streaming. Open the web viewer to watch.

## Files

| File | Role |
|------|------|
| `MainActivity.kt` | Compose UI: config, permissions, live preview, controls. |
| `CameraAgentService.kt` | Always-on foreground service; wires signaling ⇄ engine; LIVE notification. |
| `WebRtcEngine.kt` | PeerConnectionFactory, camera/mic capture, per-viewer peers. |
| `SignalingClient.kt` | WebSocket client speaking the server's JSON protocol. |
| `MotionDetector.kt` | Luma frame-differencing on the live video. |
| `Config.kt` | Persisted settings (server URL, token, motion toggle). |
