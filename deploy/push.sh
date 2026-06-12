#!/usr/bin/env bash
# Deploy the current origin/main to the VPS.
#
# Flow: push your commits to GitHub with your normal git flow first, then run
# this. It rolls the VPS's checkout to origin/main and rebuilds the stack.
# The gitignored deploy/.env (secrets) lives only on the VPS and is never touched.
#
#   ./deploy/push.sh
#
# Rollback: ssh in and `git reset --hard <good-sha> && cd deploy && docker compose up -d --build`.
set -euo pipefail

VPS="${PORTAL_VPS:-root@<vps-ip>}"
BRANCH="${PORTAL_BRANCH:-main}"

echo "==> Deploying origin/$BRANCH to $VPS"
ssh "$VPS" "cd /opt/portal \
  && git fetch --quiet origin \
  && git reset --hard origin/$BRANCH \
  && echo -n 'now at: ' && git log -1 --oneline \
  && cd deploy && docker compose up -d --build"
echo "==> Done. https://your-portal.example.com"
