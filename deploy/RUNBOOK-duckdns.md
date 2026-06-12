# Deployment runbook — DuckDNS (single household)

Stand up the Portal camera for **remote access over HTTPS/WSS with TURN**, using a
free **DuckDNS** hostname instead of a paid domain. This is the single-household
setup: you and your household viewers all see your one Portal.

Everything below is driven by `deploy/.env` (copy it from `deploy/.env.example`
and fill it in) plus two values you set during deploy: `DOMAIN` and `PUBLIC_IP`.

Replace `<your-subdomain>.duckdns.org` throughout with the hostname you pick in
step 1.

---

## 0. What you need
- A small VPS with a public IP — Ubuntu 22.04/24.04, 1 vCPU / 2 GB (Hetzner CX22 ≈ €4, or DigitalOcean/Vultr/Linode ≈ $6).
- A free DuckDNS account (sign in with GitHub/Google).
- Your Portal and at least one phone on your **home Wi-Fi** for first enrollment (see step 6 — this is the one non-obvious gotcha).

---

## 1. Create a DuckDNS hostname
At [duckdns.org](https://www.duckdns.org), create a subdomain (e.g.
`<your-subdomain>.duckdns.org`) and copy your account **token**. Put both in
`deploy/.env`:
```ini
DOMAIN=<your-subdomain>.duckdns.org
DUCKDNS_SUBDOMAIN=<your-subdomain>
DUCKDNS_TOKEN=<your-duckdns-token>
```
You do **not** set the IP by hand — `duckdns-update.sh` points the name at the VPS
automatically (step 5a).

> No DNS plugin is needed — Caddy gets the TLS cert over the HTTP challenge on port 80,
> exactly like a bought domain.

---

## 2. Prepare the host
SSH into the VPS and run the bootstrap (installs Docker, opens the firewall with
SSH protected, enables auto security updates):

```bash
sudo bash deploy/provision.sh
```

It prints the VPS public IP at the end — use that for `PUBLIC_IP` (step 4). If your
cloud has its **own** firewall/security group, open the same ports there too:
`80,443,3478/tcp`, `3478/udp`, `49160-49200/udp`.

---

## 3. Get the code onto the host
```bash
git clone <this-repo-url> /opt/portal
cd /opt/portal/deploy
```
Copy your filled `.env` up (it's gitignored, so it won't be in the clone):
```bash
# from your laptop:
scp deploy/.env root@<VPS-IP>:/opt/portal/deploy/.env
```

---

## 4. Fill in `.env`
Copy `deploy/.env.example` to `deploy/.env` and set every value. Generate the
secrets with `openssl rand -hex 32`:
```ini
DOMAIN=<your-subdomain>.duckdns.org
PUBLIC_IP=<your VPS public IP>   # so coturn advertises a reachable relay address
CAMERA_TOKEN=<random>            # provisioning bootstrap / simulator fallback
JWT_SECRET=<random>              # signs viewer tokens
ADMIN_PASSWORD=<random>          # admin console login
TURN_SECRET=<random>             # coturn shared secret
# DuckDNS creds from step 1, and VAPID keys for Web Push (npx web-push generate-vapid-keys)
```
`VIEWER_TOKEN` is intentionally blank — this server uses per-viewer auth.

---

## 5a. Point the domain at this VPS
Do this **before** launching, so DNS resolves when Caddy requests the cert:
```bash
./duckdns-update.sh                              # sets <your-subdomain>.duckdns.org -> this host's IP
dig +short <your-subdomain>.duckdns.org          # should print the VPS IP
```

## 5b. Launch
```bash
docker compose up -d --build
docker compose logs -f caddy        # watch Let's Encrypt issue the cert
```
When Caddy reports the cert obtained, open **https://<your-subdomain>.duckdns.org** —
you should see the viewer with a valid padlock.

---

## 6. Enroll your viewers — do this AT HOME ⚠️
QR enrollment requires the scanning phone to share the **camera's public IP**
(`ENROLL_SAME_NETWORK=true`). So the first time you add a device, be on the **same
home Wi-Fi as the Portal**:

1. On the Portal app, open **Viewers** → show a single-use QR (or use `/admin.html` → log in with `ADMIN_PASSWORD` → create an invite).
2. Scan it with the viewer's phone while on home Wi-Fi.
3. The phone stores a long-lived refresh token — **after this, that device can view from anywhere (cellular included).**

Point the **Portal app** itself at the server: Settings → Signaling server
`wss://<your-subdomain>.duckdns.org`. The Portal provisions its own key on first
Arm (trust-on-first-use) — no token to enter.

---

## 7. Verify (acceptance checks)
- [ ] `https://<your-subdomain>.duckdns.org` loads over valid TLS.
- [ ] `/admin.html` logs in; you can show a QR and enroll a phone.
- [ ] Portal shows **LIVE**; viewer sees video; two-way audio works.
- [ ] **TURN works:** turn the phone's Wi-Fi off (cellular), reconnect — stream still works, and `chrome://webrtc-internals` (or the console) shows a candidate of type **`relay`**. This is the key proof the NAT/`PUBLIC_IP` setup is correct.
- [ ] Motion event delivers a Web Push notification with no tab open.
- [ ] `sudo reboot` the VPS → stack comes back automatically; cert persists.

---

## 8. Cron — keep DNS fresh + back up enrollments
```bash
crontab -e
# add both lines:
*/5 * * * * /opt/portal/deploy/duckdns-update.sh    >> /var/log/duckdns.log 2>&1
0 3 * * *   /opt/portal/deploy/backup-enrollments.sh >> /var/log/portal-backup.log 2>&1
```
The first keeps `<your-subdomain>.duckdns.org` pointed at the VPS (self-heals if the
IP ever changes); the second snapshots `viewers.json` + the audit log from the
`push_data` volume.

---

## Replacing or resetting a Portal
Camera onboarding is **trust-on-first-use**: the first device claims the system,
then the slot locks. To bring on a new or factory-reset Portal:

- **Revoke the old camera** in `/admin.html` → Cameras → Revoke. That frees the
  slot; the new Portal claims it on its next Arm. **Or**
- Set `CAMERA_CLAIM_OPEN=true` in `deploy/.env`, `docker compose up -d`, Arm the
  new Portal, then set it back to `false`.

---

## Troubleshooting
| Symptom | Fix |
|---|---|
| Cert won't issue | DNS not resolving yet, or 80/443 blocked. `docker compose logs caddy`; recheck DuckDNS IP + cloud firewall. |
| Cellular can't connect / no `relay` candidate | `PUBLIC_IP` unset or wrong. Set it in `.env`, `docker compose up -d`. Test with the [Trickle ICE tool](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/) against `turn:<your-subdomain>.duckdns.org:3478`. |
| Mic blocked / mixed-content | Always use `https://` + `wss://`, never the raw IP or `http://`. |
| QR enrollment rejected | The scanning phone isn't on the camera's network. Enroll on home Wi-Fi. |
| New Portal can't connect after reset | The old camera still holds the slot — see "Replacing or resetting a Portal" above. |
| VPS IP changed | Update `PUBLIC_IP`, then `docker compose up -d` (DuckDNS self-heals via cron). |

## Restrictive networks (optional follow-up)
If some network blocks UDP entirely, enable `turns:` (TURN over TLS) on 5349 by
mounting Caddy's cert into coturn and setting `TURN_TLS=true`.
