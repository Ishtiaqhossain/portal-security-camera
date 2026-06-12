#!/usr/bin/env bash
# Point the DuckDNS subdomain at THIS host's public IP. Run ON THE VPS.
# Reads DUCKDNS_SUBDOMAIN + DUCKDNS_TOKEN from deploy/.env (gitignored).
#
# Run once after deploy, then via cron so the name self-heals if the IP changes:
#   */5 * * * * /opt/portal/deploy/duckdns-update.sh >> /var/log/duckdns.log 2>&1
#
# NOTE: run this on the VPS, not a laptop — it points the domain at whatever host
# it runs from (DuckDNS uses the request's source IP when ip= is left empty).
set -euo pipefail
cd "$(dirname "$0")"

# Load creds from .env without echoing them.
set -a; . ./.env; set +a
: "${DUCKDNS_SUBDOMAIN:?set DUCKDNS_SUBDOMAIN in .env}"
: "${DUCKDNS_TOKEN:?set DUCKDNS_TOKEN in .env}"

# Empty ip= => DuckDNS uses the caller's source IP (this VPS's public IP).
resp="$(curl -fsS "https://www.duckdns.org/update?domains=${DUCKDNS_SUBDOMAIN}&token=${DUCKDNS_TOKEN}&ip=")"
echo "$(date -Is) duckdns: ${resp}"
[ "${resp}" = "OK" ] || { echo "duckdns update failed (response: ${resp})" >&2; exit 1; }
