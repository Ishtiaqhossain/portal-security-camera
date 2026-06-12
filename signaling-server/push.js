// Web Push (VAPID) — delivers motion alerts to viewers even when no tab is open.
//
// Subscriptions are stored on disk so they survive restarts and outlive the
// WebSocket that created them (the whole point: the browser is closed when the
// alert fires). On motion the server pushes to every stored subscription via
// the browser vendor's push service; the client service worker shows the
// notification. No Google/Firebase — VAPID is the open web standard.

import webpush from 'web-push';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
// Persist outside the app dir when DATA_DIR is set (Docker mounts a volume there)
// so subscriptions survive container rebuilds.
const STORE = join(process.env.DATA_DIR || __dirname, 'subscriptions.json');

let enabled = false;
let publicKey = '';
let subs = new Map(); // endpoint -> subscription object
let lastPushMs = 0;

/** Configure VAPID from env and load persisted subscriptions. */
export function initPush() {
  publicKey = process.env.VAPID_PUBLIC_KEY || '';
  const privateKey = process.env.VAPID_PRIVATE_KEY || '';
  const subject = process.env.VAPID_SUBJECT || 'mailto:admin@example.com';
  if (publicKey && privateKey) {
    webpush.setVapidDetails(subject, publicKey, privateKey);
    enabled = true;
  }
  load();
  return { enabled, count: subs.size };
}

/** Public key the client needs to subscribe, or null if push is disabled. */
export function vapidPublicKey() {
  return enabled ? publicKey : null;
}

export function addSubscription(sub) {
  if (!enabled || !sub || !sub.endpoint) return false;
  subs.set(sub.endpoint, sub);
  persist();
  return true;
}

export function removeSubscription(endpoint) {
  if (subs.delete(endpoint)) persist();
}

/** Push a motion alert to all subscriptions, throttled and self-pruning. */
export async function sendMotionPush({ level, ts } = {}) {
  if (!enabled || subs.size === 0) return;
  const now = Date.now();
  const cooldown = Number(process.env.PUSH_COOLDOWN_MS || 60_000);
  if (now - lastPushMs < cooldown) return; // avoid notification spam
  lastPushMs = now;

  const payload = JSON.stringify({
    title: 'Portal Security',
    body: `Motion detected${level ? ` (level ${level})` : ''}`,
    ts: ts || now,
  });

  await Promise.all(
    [...subs.values()].map((sub) =>
      webpush.sendNotification(sub, payload).catch((err) => {
        // 404/410 mean the subscription is dead — drop it.
        if (err.statusCode === 404 || err.statusCode === 410) {
          removeSubscription(sub.endpoint);
        } else {
          console.warn('[push] send failed:', err.statusCode || err.message);
        }
      })
    )
  );
}

function load() {
  if (!existsSync(STORE)) return;
  try {
    const arr = JSON.parse(readFileSync(STORE, 'utf8'));
    subs = new Map(arr.map((s) => [s.endpoint, s]));
  } catch {
    subs = new Map();
  }
}

function persist() {
  try {
    writeFileSync(STORE, JSON.stringify([...subs.values()], null, 2));
  } catch (err) {
    console.warn('[push] could not persist subscriptions:', err.message);
  }
}
