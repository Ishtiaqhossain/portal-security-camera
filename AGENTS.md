# AGENTS.md — Portal Home Security Camera

Context for AI agents working in this repo. (Human overview is in `README.md`.)

## What this is

A home security camera built on an always-on **Meta Portal** device. The Portal
runs an Android app that streams its camera + mic to a browser over **WebRTC
peer-to-peer**, with **two-way audio** and **motion alerts**.

## Components

- `signaling-server/` — Node.js (ESM) WebSocket broker. Relays SDP/ICE + motion
  alerts; hands out ICE servers; serves the web client. Test: `node test-signaling.mjs`.
- `web-client/` — Browser viewer (`index.html`/`app.js`) + a webcam-based camera
  simulator (`camera-sim.html`/`camera-sim.js`). The simulator implements the
  exact same WebRTC contract as the Android app and is the reference for it.
- `portal-app/` — Android (Kotlin/Compose) camera agent. See `portal-app/README.md`.

## The WebRTC contract (keep all three implementations in sync)

- **Viewer is the offerer; camera is the answerer.**
- Viewer offers `video: recvonly` + `audio: sendrecv` (mic for talk-back).
- Camera adds its video (sendonly) + mic (sendrecv) and plays the viewer's audio.
- Signaling JSON: `register`, `offer`, `answer`, `ice`, `motion`, `peer-joined`,
  `peer-left`, `camera-online`/`camera-offline`, `welcome`, `error`, plus push:
  `subscribe-push`/`unsubscribe-push` (viewer→server) and `push-subscribed`.
  Camera addresses viewers by `to: <viewerId>`; viewer messages implicitly hit
  the camera.
- Motion alerts: camera sends `motion` → server broadcasts to open viewers AND
  fans out Web Push (VAPID) to subscribed viewers with no tab open (`push.js`,
  service worker `web-client/sw.js`). Push is GMS-free; throttled by
  `PUSH_COOLDOWN_MS`. Subscriptions persist to `subscriptions.json` (or `DATA_DIR`).
- ICE candidates are serialized as `{candidate, sdpMid, sdpMLineIndex}` (browser
  RTCIceCandidateInit shape) in both directions.

If you change the protocol or negotiation, update **server.js**, **app.js**,
**camera-sim.js**, and **SignalingClient.kt/WebRtcEngine.kt** together.

## Auth (`auth.js`)

- **Camera** registers with `cameraId`; the server replies `camera-challenge`
  (nonce), the camera replies `camera-auth { signature }` signed with its
  per-device **EC P-256 Keystore key** (verified against the provisioned public
  key). First run: `POST /camera/provision { name, publicKey }` (bootstrap via
  `CAMERA_TOKEN`) returns the cameraId. Legacy `token: CAMERA_TOKEN` register
  still works while `ALLOW_CAMERA_TOKEN` is true (simulator/migration).
  Android: `CameraIdentity.kt` (Keystore EC, SHA256withECDSA/DER).
- **Viewers** register with `accessToken: <JWT>` (per-viewer) — or legacy
  `token: VIEWER_TOKEN` if that env is still set. Enable per-viewer auth by
  setting `JWT_SECRET` + `ADMIN_PASSWORD`.
- Hardening: admin-login rate-limit/lockout + enroll throttle; security headers
  on all responses; the WS message switch is wrapped so a handler error closes
  only that socket. Signatures verified with `crypto.verify('sha256', …)` (DER).
- Enrollment is **device-initiated**: the Portal calls camera-auth
  `/camera/enroll/start` to mint a single-use ticket and renders it as a QR; the
  viewer's phone POSTs it to public `/auth/enroll` (which enforces a
  **same-network check** via the trusted-proxy XFF entry). REST:
  `/auth/{admin,enroll,refresh,config}`, camera-auth
  `/camera/{enroll/start,viewers,revoke,enable}`, admin-auth
  `/admin/{viewers,revoke,enable,audit}`. Web: `/enroll.html#t=…` (scan target),
  `/admin.html` (web console). Manage/revoke also lives in the Portal app
  (Viewers screen). `CameraApi.kt` + `QrGen.kt` on Android.
- Access tokens are short (15m) and sent over WS; refresh tokens (30d) live on
  the viewer device. Revocation kicks live sessions + blocks reconnect. JWTs are
  HS256 with alg pinned. Threat model: `SECURITY.md`.

## Portal platform constraints (from Meta's Portal docs)

- `minSdk 28`, `targetSdk 29`. Portal runs old AOSP **without Google Mobile
  Services** — no Firebase/FCM/Maps/Sign-In. Motion alerts therefore ride the
  WebSocket, not push.
- **Dark theme mandatory** (white system overlay would be invisible on light).
  Reserve the top 64dp for the overlay. Landscape-first. Touch targets ≥ 52dp.
  Body text 18sp, min 14sp. Meta blue `#0866FF`, near-white text.
- App icon: 512×512 PNG in `mipmap-xxxhdpi/`, no adaptive icons.
- Camera/mic via standard Android APIs (CameraX/Camera2 + `android.permission.CAMERA`).
- Privacy: the app must show a visible recording indicator (the LIVE badge +
  ongoing notification) and the household must be informed the camera is
  remotely viewable.

## Build / deploy

```bash
# Web stack (runnable now, no device):
cd signaling-server && npm install && cp .env.example .env && npm start
#   viewer http://localhost:8080/   camera sim http://localhost:8080/camera-sim.html

# Android app (needs JDK 17–21; Java 24 is too new for AGP 8.5/Gradle 8.9):
cd portal-app
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk     # or: hzdb app install <apk>
```

## Tooling / environment notes

- Local Android SDK had only platform `android-36` + build-tools 35/36 installed,
  and no `cmdline-tools`/`sdkmanager`. The app is configured with `compileSdk 36`
  + `buildToolsVersion 35.0.0` + `android.suppressUnsupportedCompileSdk=36` to
  build against what's present. `targetSdk` stays 29. To use the canonical
  `compileSdk 34`, install platform 34 first.
- The `hzdb` MCP server is registered with Claude Code (`claude mcp add hzdb …`).
  Its 40+ device tools load on the next session start; until then, the `hzdb`
  CLI still works from the shell (`npx -y @meta-quest/hzdb docs search …`,
  `… app install …`).

## Tests (`signaling-server/`)

`test-signaling.mjs` (11), `test-push.mjs` (6), `test-enroll.mjs` (17),
`test-camera-key.mjs` (11). Start the server first with the auth secrets in env.

## Status (see commit history for detail)

- ✅ Verified end-to-end on a real **Portal Go**: install/launch/arm, live view,
  two-way audio, motion alerts, device-QR viewer enrollment, and the Portal
  provisioning + signing with its per-device key.
- ✅ Professional Android app (dashboard, Drop In / Active modes, Viewers/QR
  screen, PIN gate, wake-lock); Web Push; remote `deploy/` stack builds.
- ⏳ Deploy to a public host for remote viewing; admin 2FA + admin camera UI open.
