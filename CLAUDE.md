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
| `signaling-server/` | Node.js (ESM) WebSocket broker. Relays SDP/ICE + motion; mints TURN creds; Web Push; per-viewer auth (`auth.js`); serves the web client. |
| `web-client/` | Browser viewer (`index.html`/`app.js`), admin console (`admin.html`/`admin.js`), invite redemption (`redeem.*`), and webcam camera simulator (`camera-sim.*`, the reference impl for the app). |
| `portal-app/` | Android (Kotlin/Compose) camera agent that runs on the Portal. |
| `deploy/` | docker-compose stack (Caddy auto-HTTPS + signaling + coturn) for remote access. |

## Commands

```bash
# Web stack — run + test locally (no device needed)
cd signaling-server && npm install && cp .env.example .env && npm start
node test-signaling.mjs            # 11 end-to-end signaling tests

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
- **Security:** `CAMERA_TOKEN` for the device; **per-viewer auth** for browsers
  via **device-initiated QR enrollment** (owner shows a single-use QR on the
  Portal → viewer scans on the same Wi-Fi → device-bound revocable token).
  Manage from the Portal's Viewers screen or `/admin.html`. Legacy shared
  `VIEWER_TOKEN` is optional/local-only.
  Media is DTLS-SRTP encrypted; the app shows a LIVE badge + notification.
  Secrets live in gitignored `.env` — never commit them. Full model in
  `SECURITY.md`.

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

## Status

- ✅ Signaling server + web viewer + camera simulator — built; 11/11 tests pass.
- ✅ Android camera agent — builds clean (APK produced); not yet run on hardware.
- ✅ Remote backend — built; compose config + image build + TURN issuance verified.
- ✅ Web Push motion alerts (VAPID) — server + service worker; 6/6 push tests pass.
- ✅ Per-viewer auth — admin console, invite links, short-lived JWTs, instant
  revocation, audit log; 18/18 auth tests pass. See `SECURITY.md`.
- ⏳ Deploy + verify on a real Portal device (needs the device + a session restart
  for hzdb MCP tools).
