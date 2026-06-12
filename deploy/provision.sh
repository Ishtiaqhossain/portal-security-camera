#!/usr/bin/env bash
# Portal security camera — one-shot VPS bootstrap for the deploy/ stack (Phase 1).
#
# Prepares a fresh Ubuntu 22.04/24.04 host: installs Docker, opens exactly the
# firewall ports the stack needs (SSH first, so you can't lock yourself out),
# and turns on automatic security updates for low-maintenance operation.
#
# Run once, as root, on the VPS:
#   sudo bash provision.sh
#
# It does NOT touch the app or secrets — after it finishes, get the repo onto the
# host, fill deploy/.env (DOMAIN + PUBLIC_IP), and `docker compose up -d --build`.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run as root:  sudo bash provision.sh" >&2
  exit 1
fi

echo "==> Installing Docker (if missing)…"
if command -v docker >/dev/null 2>&1; then
  echo "    Docker already present: $(docker --version)"
else
  curl -fsSL https://get.docker.com | sh
fi

echo "==> Configuring firewall (ufw)…"
if ! command -v ufw >/dev/null 2>&1; then
  apt-get update -y && apt-get install -y ufw
fi
# SSH FIRST — never enable ufw without an allow rule for your login path.
ufw allow OpenSSH 2>/dev/null || ufw allow 22/tcp
ufw allow 80,443,3478/tcp   # Caddy (TLS) + TURN signaling
ufw allow 3478/udp          # TURN signaling (UDP)
ufw allow 49160:49200/udp   # TURN media relay range
ufw --force enable
ufw status verbose

echo "==> Enabling automatic security updates…"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y unattended-upgrades
dpkg-reconfigure -f noninteractive unattended-upgrades || true
systemctl enable --now unattended-upgrades || true

echo
echo "================================================================"
echo "Host ready. Next steps:"
echo "  1) Get the repo here:   git clone <repo-url> portal && cd portal/deploy"
echo "  2) Edit .env:           DOMAIN=<name>.duckdns.org   PUBLIC_IP=<VPS IP>"
echo "  3) Launch:              docker compose up -d --build"
echo "  4) Watch the cert:      docker compose logs -f caddy"
PUBIP="$(curl -fsSL https://api.ipify.org 2>/dev/null || true)"
if [ -n "$PUBIP" ]; then
  echo
  echo "  This VPS's public IP looks like: $PUBIP"
  echo "  -> put that in DuckDNS and in PUBLIC_IP."
fi
echo "================================================================"
