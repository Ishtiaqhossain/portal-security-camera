# Security model

This is a camera pointed at your home, remotely viewable. The security model is
designed accordingly. This doc states what's protected, the threats, and the
residual risks.

## Assets & actors

- **Owner (admin)** — you. Manages who can view; authenticates with `ADMIN_PASSWORD`.
- **Viewers** — people you invite. Each has a revocable identity.
- **Camera** — the Portal app; authenticates with `CAMERA_TOKEN`.
- **Signaling server** — brokers the handshake and holds the secrets/credentials.
- **Sensitive asset** — the live audio/video of your home, and talk-back into it.

## What protects you

- **Media is end-to-end encrypted** (WebRTC DTLS-SRTP). The signaling server
  never sees video/audio; a network eavesdropper capturing packets cannot
  decrypt them.
- **TLS in transit** (Caddy auto-HTTPS in the deploy stack) protects tokens and
  signaling. Browsers also require a secure origin for camera/mic.
- **Device-initiated enrollment** (per-viewer auth; needs `JWT_SECRET` + `ADMIN_PASSWORD`):
  - Registration starts **on the Portal**: the owner generates a **single-use
    QR** (high-entropy token, ~2 min expiry, carried in the URL **fragment** so
    it never hits server logs). The viewer scans it to provision a credential.
  - **Same-network check**: the server only accepts enrollment if the scanning
    phone shares the camera's public IP (computed from the trusted-proxy XFF
    entry, not a spoofable one), so a relayed QR scanned off-network is rejected.
  - The credential that rides over the WebSocket is a **short-lived (15 min)
    HS256 access token**; the long-lived refresh token (30 d) is bound to the
    viewer's device and is **revocable**. Each phone enrolls once.
  - **Revocation is immediate**: revoking kicks live sessions and blocks
    reconnect even with an unexpired access token; refresh is denied.
  - **Audit log** records every redeem / connect / disconnect / revoke with time
    and IP, viewable in the admin console.
  - JWT verification **pins `alg=HS256`** (blocks alg-confusion / `none`), and
    signature comparison is constant-time.
- **Strong secrets** — `CAMERA_TOKEN`/`JWT_SECRET` are 256-bit random; not
  brute-forceable. Admin password compared in constant time.
- Secrets live in gitignored `.env`; the viewer/audit stores are gitignored too.

## Threats & status

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Viewer credential leaks | Per-viewer, short-lived, **revocable**; audit shows use | Until revoked, the holder can watch; mitigated by 15-min access tokens + revoke |
| Admin password leaks | Constant-time check; gives full control | **High if leaked** — use a strong password; rotate; consider 2FA (not yet built) |
| `CAMERA_TOKEN` leaks | Device secret, not distributed to people | Attacker could impersonate/replace the camera and spoof the feed — see below |
| Network eavesdropping | TLS + DTLS-SRTP | None for media; signaling safe under TLS |
| Brute-forcing tokens | 256-bit secrets | Negligible |
| Token in URL | Enrollment token rides the URL fragment (not logged); viewers then use refresh, not `?token=` | QR is single-use, ~2 min, same-network bound |
| Stranger self-enrolls remotely | Enrollment is device-initiated + same-network; no inbound request endpoint | Needs the on-screen QR + same Wi-Fi |
| XSS stealing tokens | No third-party scripts; refresh token in localStorage | An XSS bug would expose the refresh token (revocable) |

## Camera identity & abuse hardening (in place)

- **Per-device camera identity.** Each Portal holds a non-exportable **EC P-256
  private key** (Android Keystore) and authenticates by **signing a server nonce**
  (challenge-response). The server stores only the **public key** — a server
  breach can't impersonate a camera, there's no shared token to leak, and each
  camera is individually **revocable**. The shared `CAMERA_TOKEN` is now only a
  one-time provisioning bootstrap + simulator fallback (disable in production with
  `ALLOW_CAMERA_TOKEN=false`). This closes the old camera-spoofing gap.
- **Rate-limiting / lockout.** Admin login locks out after repeated failures (per
  IP, audited); enrollment is throttled.
- **Security headers** on every HTTP response: `nosniff`, `Referrer-Policy:
  no-referrer`, `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'`, HSTS.
- **Crash isolation.** A WebSocket handler error closes only that socket.

## Known gaps / roadmap

- **Admin 2FA.** Login is rate-limited but still single-factor. Planned: optional TOTP.
- **QR capture / first-scanner-wins.** Someone who photographs the on-screen QR
  (and is on the same Wi-Fi) within its ~2-min window could enroll their device.
  Mitigated by short expiry + single-use + same-network; backstop is the
  enrolled-devices list (and a planned "X just enrolled — Keep/Remove" prompt).
- **QR-screen PIN guard.** The enroll screen should sit behind the Portal's
  device PIN so a houseguest can't generate a QR (in progress).
- **Camera key extraction.** A dev-mode/ADB-reachable Portal exposes its token to
  anyone with physical/USB access (physical access is already largely game-over).
- **Legacy shared token.** `VIEWER_TOKEN`, if set, bypasses per-viewer auth. Keep
  it **unset** in production; it exists for local testing/back-compat only.

## Deployment checklist

- [ ] Set `JWT_SECRET` (`openssl rand -hex 32`) and a strong `ADMIN_PASSWORD`.
- [ ] Leave `VIEWER_TOKEN` **blank**.
- [ ] Serve over `https://` / `wss://` only (the `deploy/` stack does this).
- [ ] Keep `.env` off version control (it already is).
- [ ] Rotate `JWT_SECRET` if you suspect compromise — this invalidates all
      viewer sessions (everyone re-redeems an invite).
