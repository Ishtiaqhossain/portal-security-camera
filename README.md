# Portal Home Security Camera

Turn an always-on **Meta Portal** device into a remotely-viewable home security
camera. The Portal captures its camera + microphone and streams them to your web
browser over **WebRTC (peer-to-peer)**. You get a live view, **two-way audio**,
and **motion alerts** — all without the video flowing through a third-party
server (only the tiny signaling/relay service is shared infrastructure).

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
| `portal-app/`      | Android (Kotlin/Compose) app that runs on the Portal — the camera agent.    |
| `signaling-server/`| Node.js WebSocket broker for SDP/ICE exchange + motion-alert relay.         |
| `web-client/`      | Browser viewer (live video, talk-back, alerts) + a webcam-based camera simulator for testing without the device. |

## Why a server at all, if it's "peer-to-peer"?

WebRTC peers can't find each other on their own, and a phone on cellular usually
can't punch directly into your home network. The signaling server only brokers
the initial handshake and relays small alert messages; a **TURN** server is used
as a fallback media relay when direct NAT traversal fails. The actual video/audio
is end-to-end encrypted (DTLS-SRTP) and travels P2P whenever possible.

## Security & privacy

A remotely-accessible camera pointed at your home is a high-value target. This
project treats that seriously:

- **Token auth** on the signaling server (separate camera vs. viewer tokens).
- **On-device recording indicator** — the Portal app shows a visible LIVE badge
  whenever the camera is active, per Portal's household-disclosure requirement.
- **Encrypted media** end-to-end via WebRTC's mandatory DTLS-SRTP.
- You must **inform everyone in the household** that the camera may be viewed
  remotely. This is both the right thing to do and a Portal policy requirement.

## Quick start (test without a Portal, in your browser)

```bash
cd signaling-server
cp .env.example .env        # set tokens + (optional) TURN
npm install
npm start                   # serves the web client too, on http://localhost:8080
```

Then open two tabs:

- **Camera simulator** → http://localhost:8080/camera-sim.html (uses your laptop webcam)
- **Viewer**           → http://localhost:8080/ (watch the feed, talk back, see alerts)

Once that works, build and deploy the real Portal app (`portal-app/`) — it
replaces the camera simulator with the actual Portal device.

See `portal-app/README.md` for the Android build/deploy instructions and
`AGENTS.md` for Portal platform conventions.
