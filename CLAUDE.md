# CLAUDE.md

Guidance for Claude Code working in this repo. For the full WebRTC protocol
contract and Portal platform rules, see **`AGENTS.md`** (the deep reference).

## What this is

A home security camera on an always-on **Meta Portal** device. The Portal runs
an Android app that streams its camera + mic to a web browser over **WebRTC
peer-to-peer**, with **two-way audio** and **motion alerts**. A small signaling
server brokers the handshake; a TURN server relays media across NATs.

## Layout

| Path | What |
|------|------|
| `signaling-server/` | Node.js (ESM) broker. Relays SDP/ICE + motion; mints TURN creds; Web Push; per-viewer auth + enrollment + per-device camera identity (`auth.js`); serves the web client. |
| `web-client/` | Browser viewer (`index.html`/`app.js`), enroll page (`enroll.*`), admin console (`admin.*`), and webcam camera simulator (`camera-sim.*`, the reference impl for the app). |
| `portal-app/` | Android (Kotlin/Compose) camera agent that runs on the Portal. |
| `deploy/` | docker-compose stack (Caddy auto-HTTPS + signaling + coturn) for remote access. |

## Commands

```bash
# Web stack — run + test locally (no device needed)
cd signaling-server && npm install && cp .env.example .env && npm start
# tests (start server first, with CAMERA_TOKEN/JWT_SECRET/ADMIN_PASSWORD in env):
node test-signaling.mjs && node test-push.mjs && node test-enroll.mjs && node test-camera-key.mjs

# Android app — needs JDK 17–21 (NOT the default Java 24)
cd portal-app
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk   # or: hzdb app install <apk>

# Remote backend
cd deploy && cp .env.example .env  # set DOMAIN + secrets
docker compose up -d --build
```

## Conventions

- **WebRTC roles:** viewer is the offerer, camera is the answerer. Camera sends
  video (sendonly) + mic (sendrecv); viewer sends mic for talk-back. If you
  change signaling or negotiation, update **all** of `server.js`, `app.js`,
  `camera-sim.js`, and `SignalingClient.kt`/`WebRtcEngine.kt` together. Full
  protocol in `AGENTS.md`.
- **Portal/Android:** `minSdk 28`, `targetSdk 29`, **no Google Mobile Services**
  (no Firebase/FCM — alerts ride the WebSocket). Dark theme mandatory, top 64dp
  reserved for the system overlay, landscape-first, touch targets ≥ 52dp, icon
  512×512 in `mipmap-xxxhdpi/`.
- **Security** (full model in `SECURITY.md`):
  - **Viewers:** device-initiated **QR enrollment** (single-use QR on the Portal
    → scan on the same Wi-Fi → device-bound revocable token; short-lived HS256
    access + 30-day refresh). Manage from the Portal Viewers screen or `/admin.html`.
  - **Camera:** per-device **EC P-256 key in the Android Keystore**; authenticates
    by **signing a server nonce** (challenge-response). Shared `CAMERA_TOKEN` is
    now only a provisioning bootstrap + simulator fallback (`ALLOW_CAMERA_TOKEN`).
  - Admin-login rate-limit/lockout, security headers, per-socket error boundary;
    media DTLS-SRTP encrypted; LIVE badge on the app.
  - **App PIN:** owner sets a 4-digit PIN on first run (PBKDF2-salted in
    `PinManager`, failure lockout). The dashboard opens freely; the PIN gates
    **individual sensitive actions** — Arm, Disarm, open Viewers, open Settings,
    and back-to-exit **while armed** — via a modal `PinPrompt` overlay (`PinScreens.kt`).
    Backgrounding the app **fails safe to Disarmed** (onStop stops the foreground
    service) — never leaves a frozen, un-resumable feed. (Replaced the old
    KeyguardManager device-credential gate.)
  - Legacy `VIEWER_TOKEN` is optional/local-only. Secrets in gitignored `.env`.

## Environment gotchas (this machine)

- **Java:** system default is Java 24, too new for AGP 8.5 / Gradle 8.9. Use the
  Android Studio JBR (JDK 21) via `JAVA_HOME` for any Gradle command.
- **Android SDK:** only platform `android-36` + build-tools 35/36 are installed,
  and there's no `sdkmanager`. The app uses `compileSdk 36` +
  `buildToolsVersion 35.0.0` + `android.suppressUnsupportedCompileSdk=36` to
  build against what's present (`targetSdk` stays 29). To revert to the canonical
  `compileSdk 34`, install platform 34 first.
- **hzdb MCP:** registered with Claude Code (`claude mcp add hzdb …`). The 40+
  device tools load on the **next session start**; until then the `hzdb` CLI
  still works from the shell (`npx -y @meta-quest/hzdb docs search …`,
  `… app install …`).
- A local dev signaling server may be running in the background on `:8080` for
  browser testing.

## Tests

`signaling-server/`: `test-signaling.mjs` (11), `test-push.mjs` (6),
`test-enroll.mjs` (17), `test-camera-key.mjs` (11). All passing.

## Status

- ✅ Full system runs on a real **Portal Go** — install/launch/arm, live view,
  two-way audio, and motion alerts verified on hardware.
- ✅ Professional Android app — status dashboard (no on-device feed), Drop In /
  Active modes, Viewers/QR enrollment screen, device-PIN gate, wake-lock.
- ✅ Per-viewer device-QR enrollment + per-device camera key — verified on device
  (Portal provisions its key and registers by signature).
- ✅ Web Push motion alerts (VAPID); remote backend (Caddy + signaling + coturn)
  builds & TURN issuance verified.
- ⏳ Deploy to a public host for true remote viewing (stack ready; needs a VPS +
  domain). Admin 2FA + admin-console camera UI still open (see `SECURITY.md`).
