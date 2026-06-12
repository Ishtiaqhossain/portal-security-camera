// Portal home-security signaling server.
//
// Brokers the WebRTC handshake between the Portal camera agent and browser
// viewers, and relays small control messages (motion alerts, talk-back state).
// Media itself never passes through here — it goes peer-to-peer over WebRTC.
//
// Roles:
//   - "camera": the Portal app. At most one connected per cameraId; a new one
//     replaces the old. Authenticates with CAMERA_TOKEN.
//   - "viewer": a browser. Many allowed. Authenticates with VIEWER_TOKEN.
//
// Wire protocol (JSON over WebSocket). Every message has a `type`.
//   client -> server: register, offer, answer, ice, motion, bye,
//                     subscribe-push, unsubscribe-push
//   server -> client: welcome, peer-joined, peer-left, offer, answer, ice,
//                     motion, push-subscribed, error
// Routing: viewer messages (offer/answer/ice) implicitly target the camera.
// Camera messages carry `to` (a viewer id). Motion is camera -> all viewers,
// and also fan-out as Web Push to subscribed viewers (even with no tab open).

import http from 'node:http';
import crypto from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join, normalize, extname } from 'node:path';
import { WebSocketServer } from 'ws';
import { initPush, vapidPublicKey, addSubscription, removeSubscription, sendMotionPush } from './push.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = join(__dirname, '..', 'web-client');

// Load .env (Node 20.12+ built-in). Without this, the tokens below fall back to
// their insecure defaults and any real token is rejected as invalid.
try {
  process.loadEnvFile(join(__dirname, '.env'));
} catch {
  // No .env file — fine, env vars may be set another way.
}

const push = initPush();

const PORT = Number(process.env.PORT || 8080);
const CAMERA_TOKEN = process.env.CAMERA_TOKEN || 'change-me-camera';
const VIEWER_TOKEN = process.env.VIEWER_TOKEN || 'change-me-viewer';
const CAMERA_ID = process.env.CAMERA_ID || 'home';

// Build the ICE server list handed to each client at registration. STUN is
// always included. TURN comes in two flavors:
//   - Ephemeral creds (preferred): set TURN_HOST + TURN_SECRET. We mint a
//     time-limited username/credential per client using coturn's
//     `use-auth-secret` scheme (username = expiry unix ts, credential =
//     base64(HMAC-SHA1(secret, username))). No long-lived passwords in clients.
//   - Static creds: set TURN_URL + TURN_USERNAME + TURN_CREDENTIAL.
function iceServers() {
  const servers = [{ urls: process.env.STUN_URL || 'stun:stun.l.google.com:19302' }];

  const turnHost = process.env.TURN_HOST;
  const turnSecret = process.env.TURN_SECRET;
  if (turnHost && turnSecret) {
    const ttl = Number(process.env.TURN_TTL || 86400);
    const username = String(Math.floor(Date.now() / 1000) + ttl);
    const credential = crypto.createHmac('sha1', turnSecret).update(username).digest('base64');
    servers.push({ urls: `turn:${turnHost}:3478?transport=udp`, username, credential });
    servers.push({ urls: `turn:${turnHost}:3478?transport=tcp`, username, credential });
    if (process.env.TURN_TLS === 'true') {
      servers.push({ urls: `turns:${turnHost}:5349?transport=tcp`, username, credential });
    }
  } else if (process.env.TURN_URL) {
    servers.push({
      urls: process.env.TURN_URL,
      username: process.env.TURN_USERNAME || '',
      credential: process.env.TURN_CREDENTIAL || '',
    });
  }
  return servers;
}

// --- Static file server for the web client (dev convenience) ----------------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webmanifest': 'application/manifest+json',
};

const httpServer = http.createServer(async (req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
    return;
  }
  // Map URL to a file inside WEB_ROOT, defaulting to index.html.
  let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  if (urlPath === '/') urlPath = '/index.html';
  const filePath = normalize(join(WEB_ROOT, urlPath));
  if (!filePath.startsWith(WEB_ROOT)) {
    res.writeHead(403);
    res.end('forbidden');
    return;
  }
  try {
    const body = await readFile(filePath);
    res.writeHead(200, { 'content-type': MIME[extname(filePath)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end('not found');
  }
});

// --- WebSocket signaling -----------------------------------------------------

const wss = new WebSocketServer({ server: httpServer });

let nextId = 1;
/** @type {Map<string, {ws: import('ws').WebSocket, role: string, id: string}>} */
const clients = new Map();
let camera = null; // the single connected camera peer, or null

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

function viewers() {
  return [...clients.values()].filter((c) => c.role === 'viewer');
}

function broadcastToViewers(obj) {
  for (const v of viewers()) send(v.ws, obj);
}

wss.on('connection', (ws) => {
  const peer = { ws, role: null, id: String(nextId++) };

  // Drop clients that don't authenticate promptly.
  const authTimer = setTimeout(() => {
    if (!peer.role) {
      send(ws, { type: 'error', code: 'auth_timeout', message: 'register within 10s' });
      ws.close();
    }
  }, 10_000);

  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      send(ws, { type: 'error', code: 'bad_json', message: 'invalid JSON' });
      return;
    }

    // Must register first.
    if (!peer.role && msg.type !== 'register') {
      send(ws, { type: 'error', code: 'not_registered', message: 'register first' });
      return;
    }

    switch (msg.type) {
      case 'register': {
        const wantCamera = msg.role === 'camera';
        const token = wantCamera ? CAMERA_TOKEN : VIEWER_TOKEN;
        if (msg.role !== 'camera' && msg.role !== 'viewer') {
          send(ws, { type: 'error', code: 'bad_role', message: 'role must be camera|viewer' });
          return;
        }
        if (msg.token !== token) {
          send(ws, { type: 'error', code: 'bad_token', message: 'invalid token' });
          ws.close();
          return;
        }
        clearTimeout(authTimer);
        peer.role = msg.role;
        clients.set(peer.id, peer);

        if (wantCamera) {
          // Replace any existing camera.
          if (camera && camera.ws !== ws) {
            send(camera.ws, { type: 'error', code: 'replaced', message: 'another camera connected' });
            camera.ws.close();
          }
          camera = peer;
          send(ws, { type: 'welcome', id: peer.id, role: peer.role, cameraId: CAMERA_ID, iceServers: iceServers() });
          // Tell existing viewers the camera is (re)available.
          broadcastToViewers({ type: 'camera-online' });
        } else {
          send(ws, {
            type: 'welcome',
            id: peer.id,
            role: peer.role,
            cameraId: CAMERA_ID,
            iceServers: iceServers(),
            cameraOnline: !!camera,
            vapidPublicKey: vapidPublicKey(),
          });
          // Let the camera know a viewer wants in (so it can prepare).
          if (camera) send(camera.ws, { type: 'peer-joined', id: peer.id });
        }
        console.log(`[+] ${peer.role} ${peer.id} registered (${clients.size} clients)`);
        return;
      }

      // Viewer -> camera signaling.
      case 'offer':
      case 'answer':
      case 'ice': {
        if (peer.role === 'viewer') {
          if (!camera) {
            send(ws, { type: 'error', code: 'no_camera', message: 'camera offline' });
            return;
          }
          send(camera.ws, { ...msg, from: peer.id });
        } else {
          // Camera -> a specific viewer.
          const target = clients.get(msg.to);
          if (target) send(target.ws, { ...msg, from: peer.id });
        }
        return;
      }

      // Camera -> all viewers (live, for open tabs) + Web Push (for closed tabs).
      case 'motion': {
        if (peer.role !== 'camera') return;
        broadcastToViewers({ type: 'motion', level: msg.level, ts: msg.ts || null });
        sendMotionPush({ level: msg.level, ts: msg.ts });
        return;
      }

      // Viewer registers/removes a Web Push subscription.
      case 'subscribe-push': {
        if (peer.role !== 'viewer') return;
        const ok = addSubscription(msg.subscription);
        send(ws, { type: 'push-subscribed', ok });
        return;
      }
      case 'unsubscribe-push': {
        if (peer.role !== 'viewer') return;
        if (msg.endpoint) removeSubscription(msg.endpoint);
        return;
      }

      case 'bye': {
        ws.close();
        return;
      }

      default:
        send(ws, { type: 'error', code: 'unknown_type', message: `unknown type ${msg.type}` });
    }
  });

  ws.on('close', () => {
    clearTimeout(authTimer);
    clients.delete(peer.id);
    if (camera === peer) {
      camera = null;
      broadcastToViewers({ type: 'camera-offline' });
    }
    if (peer.role === 'camera' && camera) {
      // no-op
    }
    if (peer.role === 'viewer' && camera) {
      send(camera.ws, { type: 'peer-left', id: peer.id });
    }
    if (peer.role) console.log(`[-] ${peer.role} ${peer.id} left (${clients.size} clients)`);
  });
});

// Heartbeat: terminate sockets that stop responding.
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);
wss.on('close', () => clearInterval(heartbeat));

httpServer.listen(PORT, () => {
  console.log(`signaling-server listening on http://localhost:${PORT}`);
  console.log(`  viewer:          http://localhost:${PORT}/`);
  console.log(`  camera simulator http://localhost:${PORT}/camera-sim.html`);
  console.log(`  cameraId=${CAMERA_ID}  iceServers=${JSON.stringify(iceServers())}`);
  console.log(`  webPush=${push.enabled ? `enabled (${push.count} stored subs)` : 'disabled (set VAPID_* to enable)'}`);
});
