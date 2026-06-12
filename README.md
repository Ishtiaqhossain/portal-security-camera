# Portal Home Security Camera

Turn an always-on **Meta Portal** device into a remotely-viewable home security
camera. The Portal captures its camera + microphone and streams them to your web
browser over **WebRTC (peer-to-peer)**. The video never flows through a
third-party server (only the tiny signaling/relay service is shared infra).

Features:

- **Live view + two-way audio** from any browser.
- **Motion alerts** — live in the tab and via **Web Push** (reaches you with no
  tab open; VAPID, no Google/Firebase).
- **Two capture modes** — *Drop In* (camera wakes only when a viewer connects)
  and *Active* (streams continuously, enabling motion detection while you're away).
- **Per-viewer access** — owner-managed, revocable, no shared password (see below).
- A professional on-device dashboard (no live feed shown on the Portal itself),
  with a device-PIN gate.

```
   ┌────────────────┐     SDP / ICE / motion      ┌────────────────────┐
   │  Portal app    │◄──────── WebSocket ─────────►│  signaling-server  │
   │ (Android,      │                              │  (Node.js)         │
   │  camera agent) │                              └─────────┬──────────┘
   └───────┬────────┘                                        │ SDP / ICE / motion
           │                                                 ▼
           │           WebRTC media (P2P, encrypted)   ┌────────────┐
           └────────────── video / audio ─────────────►│ web-client │
                          ◄──── talk-back audio ────────│ (browser)  │
                                                        └────────────┘
```

## Components

| Directory          | What it is                                                                 |
|--------------------|----------------------------------------------------------------------------|
| `portal-app/`      | Android (Kotlin/Compose) app on the Portal — camera agent, status dashboard, and the Viewers/QR enrollment screen. |
| `signaling-server/`| Node.js broker: SDP/ICE relay, motion + Web Push, per-viewer auth + enrollment, per-device camera identity, and serves the web client. |
| `web-client/`      | Browser viewer (`index.html`), invite/enroll page (`enroll.html`), admin console (`admin.html`), and a webcam camera simulator for testing without the device. |
| `deploy/`          | docker-compose stack (Caddy auto-HTTPS + signaling + coturn TURN) for real remote access. |

## Why a server at all, if it's "peer-to-peer"?

WebRTC peers can't find each other on their own, and a phone on cellular usually
can't punch directly into your home network. The signaling server only brokers
the initial handshake and relays small alert messages; a **TURN** server is used
as a fallback media relay when direct NAT traversal fails. The actual video/audio
is end-to-end encrypted (DTLS-SRTP) and travels P2P whenever possible.

## Security & privacy

A remotely-accessible camera pointed at your home is a high-value target. This
project treats that seriously (full threat model in **`SECURITY.md`**):

- **Device-initiated viewer enrollment.** No shared viewer password. The owner
  generates a **single-use QR on the Portal**; a viewer scans it **on the same
  Wi-Fi** (server-enforced) to get a **device-bound, revocable** identity. Sessions
  are short-lived signed tokens (HS256 JWT), revocation is instant + audited.
- **Per-device camera identity.** Each Portal holds a non-exportable **EC P-256
  key (Android Keystore)** and authenticates by **signing a server nonce** — the
  server stores only the public key, so a leaked token can't impersonate the
  camera, and each camera is individually revocable.
- **Encrypted media** end-to-end via WebRTC's mandatory DTLS-SRTP; **TLS** in
  transit (Caddy auto-HTTPS in `deploy/`).
- **Abuse resistance** — admin-login lockout, enrollment throttling, security
  headers, and a per-socket error boundary.
- **On-device disclosure** — a visible LIVE badge + persistent notification while
  the camera is active, and a **device-PIN gate** on the app. Per Portal policy
  (and basic decency), **inform everyone in the household** the camera is viewable.
- Secrets live in gitignored `.env` files — never committed.

## Quick start (test without a Portal, in your browser)

```bash
cd signaling-server
cp .env.example .env   # set CAMERA_TOKEN + JWT_SECRET + ADMIN_PASSWORD
                       # (openssl rand -hex 32). For a no-friction local viewer,
                       # also set VIEWER_TOKEN to enable the legacy token field.
npm install
npm start              # serves the web client too, on http://localhost:8080

node test-signaling.mjs && node test-enroll.mjs && node test-camera-key.mjs   # test suites
```

Then:

- **Camera simulator** → http://localhost:8080/camera-sim.html (uses your laptop
  webcam; registers via the legacy `CAMERA_TOKEN`).
- **Viewer** → http://localhost:8080/ — needs an enrolled device. Either set
  `VIEWER_TOKEN` (legacy field appears), or mint an enroll link from the
  **admin console** (http://localhost:8080/admin.html) and open it in one window.

Once that works, build and deploy the real Portal app (`portal-app/`) — it
replaces the simulator with the actual Portal, which provisions its own camera
key and shows a **QR on its Viewers screen** to enroll your phone/laptop.

See `portal-app/README.md` for Android build/deploy, `SECURITY.md` for the
security model, and `AGENTS.md` for the protocol + Portal conventions.
