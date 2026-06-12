# Deploying the backend (remote access)

This brings up everything needed to reach your Portal camera from outside your
home network, over HTTPS/WSS, with a TURN relay for NAT traversal:

- **Caddy** — reverse proxy with automatic Let's Encrypt TLS.
- **signaling** — the WebSocket broker (also serves the web viewer).
- **coturn** — TURN/STUN relay.

Everything is validated locally (compose config, image build, TURN credential
issuance). What you provide is a host + a domain name.

## What you need

1. A small **VPS** with a public IP (Ubuntu 22.04+; 1 vCPU / 1 GB is plenty).
   DigitalOcean, Hetzner, Vultr, Linode, etc.
2. A **domain name** you control, e.g. `cam.example.com`.

## Steps

### 1. Point DNS at the host

Create a DNS **A record**: `cam.example.com → <your VPS IP>` (and an AAAA record
if the VPS has IPv6). Wait for it to resolve (`dig cam.example.com`).

### 2. Install Docker on the VPS

```bash
curl -fsSL https://get.docker.com | sh
```

### 3. Open the firewall

```bash
# 80/443 for Caddy (TLS), 3478 for TURN signaling, 49160-49200/udp for relay.
sudo ufw allow 80,443,3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 49160:49200/udp
```
If your cloud provider has its own firewall/security group, open the same ports there.

### 4. Get the code onto the VPS and configure

```bash
git clone <this-repo> portal && cd portal/deploy
cp .env.example .env
```
Edit `.env`:
- `DOMAIN` — your domain (e.g. `cam.example.com`).
- `CAMERA_TOKEN`, `VIEWER_TOKEN` — `openssl rand -hex 32` each (or reuse your existing ones).
- `TURN_SECRET` — `openssl rand -hex 32`.

### 5. Launch

```bash
docker compose up -d --build
docker compose logs -f          # watch Caddy obtain the TLS cert
```

Caddy needs ports 80/443 reachable and DNS pointing at the host to issue the
cert. Once it does, open **https://cam.example.com** — you should see the viewer.

### 6. Point the clients at it

- **Web viewer**: open `https://cam.example.com`, leave "Signaling server" blank
  (same origin), enter the **VIEWER_TOKEN**.
- **Portal app**: in the app's "Signaling server" field enter
  `wss://cam.example.com`, and the **CAMERA_TOKEN**.

That's it — the Portal streams out, your browser connects in, and TURN relays
the media when a direct path isn't available.

## Verifying TURN actually works

In the viewer, open the browser console; during connection you'll see ICE
candidates. A working TURN relay produces candidates of type `relay`. You can
also test your TURN server with the
[Trickle ICE tool](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/)
using `turn:cam.example.com:3478`, username/credential from a `welcome` message.

## Common gotchas

- **No `relay` candidates / cellular can't connect.** Some clouds put the VPS
  behind a NAT (the host's IP isn't on its NIC). Add coturn's external mapping:
  in `docker-compose.yml` append `--external-ip=<PUBLIC_IP>` to the coturn
  `command`, then `docker compose up -d`.
- **Cert won't issue.** DNS not resolving yet, or ports 80/443 blocked. Check
  `docker compose logs caddy`.
- **Mixed-content / mic blocked.** Always use `https://` and `wss://` — never the
  raw IP or `http://`. Browsers require a secure origin for camera/mic and to
  open `wss://`.

## No domain? Two tunneling alternatives

- **Cloudflare Tunnel** — `cloudflared` exposes the signaling service on an HTTPS
  subdomain without opening ports. (TURN still needs reachable UDP, so pair with
  a STUN-only setup or a hosted TURN.)
- **Tailscale** — put the Portal and your phone on the same tailnet and skip
  public exposure entirely; point the app/viewer at the Portal's tailnet name.
  Most private option; requires the Tailscale app on each viewing device.

## Enabling TURN over TLS (optional, for restrictive networks)

`turns:` on port 443/5349 punches through firewalls that block UDP. It needs a
cert for `${DOMAIN}` mounted into coturn (`--cert`/`--pkey`), then set
`TURN_TLS=true`. Caddy already fetches that cert into its `caddy_data` volume;
mount it into coturn and point `--cert`/`--pkey` at it. Ask and I'll wire it up.
