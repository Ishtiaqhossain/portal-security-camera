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
import * as auth from './auth.js';

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
const authState = auth.initAuth();

const PORT = Number(process.env.PORT || 8080);
const CAMERA_TOKEN = process.env.CAMERA_TOKEN || 'change-me-camera';
// Legacy shared viewer token. When per-viewer auth (JWT) is enabled, this is a
// fallback you can disable by unsetting VIEWER_TOKEN. Leave set for local tests.
const VIEWER_TOKEN = process.env.VIEWER_TOKEN || '';
const CAMERA_ID = process.env.CAMERA_ID || 'home';
// Allow the legacy shared CAMERA_TOKEN to register a camera directly (used by
// the browser camera simulator and during migration). Set false in production
// once every camera has its own provisioned key.
const ALLOW_CAMERA_TOKEN = process.env.ALLOW_CAMERA_TOKEN !== 'false';

// --- Security headers + a tiny in-memory rate limiter -----------------------

function setSecurityHeaders(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Content-Security-Policy', "frame-ancestors 'none'");
  // Honored only over HTTPS; harmless over http.
  res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
}

const rlBuckets = new Map(); // key -> { hits: number[], lockedUntil: number }
/** Returns true if `key` is currently rate-limited (and records lockout). */
function rateLimited(key, max, windowMs, lockoutMs) {
  const now = Date.now();
  let b = rlBuckets.get(key);
  if (!b) { b = { hits: [], lockedUntil: 0 }; rlBuckets.set(key, b); }
  if (b.lockedUntil > now) return true;
  b.hits = b.hits.filter((t) => now - t < windowMs);
  if (b.hits.length >= max) { b.lockedUntil = now + lockoutMs; return true; }
  return false;
}
function rlHit(key) {
  const b = rlBuckets.get(key) || { hits: [], lockedUntil: 0 };
  b.hits.push(Date.now());
  rlBuckets.set(key, b);
}
function rlClear(key) { rlBuckets.delete(key); }

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

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(body);
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 1_000_000) req.destroy(); // cap body size
    });
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch { resolve(null); }
    });
    req.on('error', () => resolve(null));
  });
}

function clientIp(req) {
  // Behind a single trusted proxy (Caddy), the real client IP is the LAST entry
  // it appends to X-Forwarded-For. Taking the first would let a client spoof the
  // header (e.g. to fake being on the camera's network during enrollment).
  const xff = (req.headers['x-forwarded-for'] || '')
    .split(',').map((s) => s.trim()).filter(Boolean);
  if (xff.length) return xff[xff.length - 1];
  return req.socket?.remoteAddress || 'unknown';
}

// REST API for auth + admin. Returns true if it handled the request.
async function handleApi(req, res) {
  const url = req.url.split('?')[0];
  const method = req.method;

  // --- public auth endpoints ---
  if (url === '/auth/config' && method === 'GET') {
    return sendJson(res, 200, { authEnabled: auth.isEnabled(), legacyToken: !!VIEWER_TOKEN });
  }
  if (url === '/auth/admin' && method === 'POST') {
    const ip = clientIp(req);
    const rlKey = `admin:${ip}`;
    if (rateLimited(rlKey, 5, 15 * 60_000, 15 * 60_000)) {
      auth.record('admin_login_lockout', { ip });
      return sendJson(res, 429, { error: 'too many attempts — locked out, try again later' });
    }
    const body = await readJsonBody(req);
    const token = body && auth.adminLogin(body.password);
    if (!token) { rlHit(rlKey); auth.record('admin_login_fail', { ip }); return sendJson(res, 401, { error: 'invalid password' }); }
    rlClear(rlKey);
    auth.record('admin_login', { ip });
    return sendJson(res, 200, { adminToken: token });
  }
  if (url === '/auth/enroll' && method === 'POST') {
    const ip = clientIp(req);
    if (rateLimited(`enroll:${ip}`, 30, 60_000, 60_000)) {
      return sendJson(res, 429, { error: 'too many attempts, slow down' });
    }
    rlHit(`enroll:${ip}`);
    const body = await readJsonBody(req);
    const result = body ? auth.enroll(body.token, clientIp(req)) : { error: 'invalid' };
    if (result.error) {
      auth.record('enroll_fail', { reason: result.error, ip: clientIp(req) });
      const msg = result.error === 'network'
        ? 'You must be on the same Wi-Fi as the camera to enroll.'
        : 'This enrollment code is invalid or has expired.';
      return sendJson(res, 400, { error: msg, reason: result.error });
    }
    auth.record('enroll', { viewerId: result.viewer.id, name: result.viewer.name, ip: clientIp(req) });
    notifyCameraEnrolled(result.viewer);
    return sendJson(res, 200, result);
  }
  if (url === '/auth/refresh' && method === 'POST') {
    const body = await readJsonBody(req);
    const result = body && auth.refresh(body.refreshToken);
    if (!result) return sendJson(res, 401, { error: 'refresh denied' });
    return sendJson(res, 200, result);
  }

  // --- camera-authenticated endpoints (the Portal manages its own access) ---
  if (url.startsWith('/camera/')) {
    if (!requireCamera(req)) return sendJson(res, 401, { error: 'camera auth required' });
    // Bootstrap: a camera registers its public key once, using the shared
    // CAMERA_TOKEN. Afterwards it authenticates by signing nonces (no token).
    if (url === '/camera/provision' && method === 'POST') {
      const body = await readJsonBody(req);
      const cam = body && auth.provisionCamera(body.name, body.publicKey);
      if (!cam) return sendJson(res, 400, { error: 'invalid public key' });
      auth.record('camera_provision', { cameraId: cam.id, name: cam.name, ip: clientIp(req) });
      return sendJson(res, 200, cam);
    }
    if (url === '/camera/enroll/start' && method === 'POST') {
      const body = await readJsonBody(req);
      return sendJson(res, 200, auth.createEnrollTicket(body?.name, clientIp(req)));
    }
    if (url === '/camera/viewers' && method === 'GET') {
      return sendJson(res, 200, { viewers: auth.listViewers() });
    }
    if (url === '/camera/revoke' && method === 'POST') {
      const body = await readJsonBody(req);
      const v = body && auth.setRevoked(body.id, true);
      if (!v) return sendJson(res, 404, { error: 'no such viewer' });
      auth.record('revoke', { viewerId: v.id, name: v.name, by: 'camera' });
      kickViewer(v.id);
      return sendJson(res, 200, { viewer: v });
    }
    if (url === '/camera/enable' && method === 'POST') {
      const body = await readJsonBody(req);
      const v = body && auth.setRevoked(body.id, false);
      if (!v) return sendJson(res, 404, { error: 'no such viewer' });
      auth.record('enable', { viewerId: v.id, name: v.name, by: 'camera' });
      return sendJson(res, 200, { viewer: v });
    }
    return sendJson(res, 404, { error: 'not found' });
  }

  // --- admin-only endpoints (web console: view/revoke/audit) ---
  if (url.startsWith('/admin/')) {
    if (!auth.requireAdmin(req.headers['authorization'])) {
      return sendJson(res, 401, { error: 'admin auth required' });
    }
    if (url === '/admin/viewers' && method === 'GET') {
      return sendJson(res, 200, { viewers: auth.listViewers() });
    }
    if (url === '/admin/revoke' && method === 'POST') {
      const body = await readJsonBody(req);
      const v = body && auth.setRevoked(body.id, true);
      if (!v) return sendJson(res, 404, { error: 'no such viewer' });
      auth.record('revoke', { viewerId: v.id, name: v.name });
      kickViewer(v.id); // drop any live sessions immediately
      return sendJson(res, 200, { viewer: v });
    }
    if (url === '/admin/enable' && method === 'POST') {
      const body = await readJsonBody(req);
      const v = body && auth.setRevoked(body.id, false);
      if (!v) return sendJson(res, 404, { error: 'no such viewer' });
      auth.record('enable', { viewerId: v.id, name: v.name });
      return sendJson(res, 200, { viewer: v });
    }
    if (url === '/admin/audit' && method === 'GET') {
      return sendJson(res, 200, { audit: auth.listAudit() });
    }
    if (url === '/admin/cameras' && method === 'GET') {
      return sendJson(res, 200, { cameras: auth.listCameras() });
    }
    if (url === '/admin/camera-revoke' && method === 'POST') {
      const body = await readJsonBody(req);
      const c = body && auth.setCameraRevoked(body.id, true);
      if (!c) return sendJson(res, 404, { error: 'no such camera' });
      auth.record('camera_revoke', { cameraId: c.id, name: c.name });
      kickCamera(c.id); // drop it if currently connected
      return sendJson(res, 200, { camera: c });
    }
    if (url === '/admin/camera-enable' && method === 'POST') {
      const body = await readJsonBody(req);
      const c = body && auth.setCameraRevoked(body.id, false);
      if (!c) return sendJson(res, 404, { error: 'no such camera' });
      auth.record('camera_enable', { cameraId: c.id, name: c.name });
      return sendJson(res, 200, { camera: c });
    }
  }

  return sendJson(res, 404, { error: 'not found' });
}

const httpServer = http.createServer(async (req, res) => {
  setSecurityHeaders(res);
  if (req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end('ok');
    return;
  }
  if (req.url.startsWith('/auth/') || req.url.startsWith('/admin/') || req.url.startsWith('/camera/')) {
    return handleApi(req, res);
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
    res.writeHead(200, {
      'content-type': MIME[extname(filePath)] || 'application/octet-stream',
      'cache-control': 'no-cache, no-store, must-revalidate', // always get fresh client code
    });
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

// Immediately drop any live sessions for a revoked viewer.
function kickViewer(viewerId) {
  for (const c of clients.values()) {
    if (c.role === 'viewer' && c.viewer && c.viewer.id === viewerId) {
      send(c.ws, { type: 'error', code: 'revoked', message: 'access revoked' });
      c.ws.close();
    }
  }
}

// Complete a camera registration (after legacy-token or signature verification).
function finalizeCameraRegister(peer, label) {
  clearTimeout(peer.authTimer);
  peer.role = 'camera';
  peer.cameraLabel = label;
  clients.set(peer.id, peer);
  // Replace any existing camera.
  if (camera && camera.ws !== peer.ws) {
    send(camera.ws, { type: 'error', code: 'replaced', message: 'another camera connected' });
    camera.ws.close();
  }
  camera = peer;
  send(peer.ws, { type: 'welcome', id: peer.id, role: 'camera', cameraId: CAMERA_ID, iceServers: iceServers() });
  broadcastToViewers({ type: 'camera-online' });
  auth.record('camera_connect', { camera: label, ip: peer.ip });
  console.log(`[+] camera ${peer.id} (${label}) registered (${clients.size} clients)`);
}

// Drop the connected camera if its identity was just revoked.
function kickCamera(cameraId) {
  if (camera && camera.cameraLabel === cameraId) {
    send(camera.ws, { type: 'error', code: 'revoked', message: 'camera access revoked' });
    camera.ws.close();
  }
}

// Constant-time check that a request carries the camera's token.
function requireCamera(req) {
  const t = (req.headers['authorization'] || '').replace(/^Bearer\s+/i, '');
  if (!t || t.length !== CAMERA_TOKEN.length) return false;
  return crypto.timingSafeEqual(Buffer.from(t), Buffer.from(CAMERA_TOKEN));
}

// Tell the Portal a device just enrolled (so it can show "X enrolled — Keep/Remove").
function notifyCameraEnrolled(viewer) {
  if (camera) send(camera.ws, { type: 'viewer-enrolled', id: viewer.id, name: viewer.name });
}

wss.on('connection', (ws, req) => {
  const peer = { ws, role: null, id: String(nextId++), ip: clientIp(req), viewer: null };

  // Drop clients that don't authenticate promptly (covers the camera's
  // challenge-response round-trip too).
  peer.authTimer = setTimeout(() => {
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

    // Must register first ('camera-auth' is part of the camera's register handshake).
    if (!peer.role && msg.type !== 'register' && msg.type !== 'camera-auth') {
      send(ws, { type: 'error', code: 'not_registered', message: 'register first' });
      return;
    }

    try {
    switch (msg.type) {
      case 'register': {
        if (msg.role !== 'camera' && msg.role !== 'viewer') {
          send(ws, { type: 'error', code: 'bad_role', message: 'role must be camera|viewer' });
          return;
        }
        if (msg.role === 'camera') {
          // Preferred: per-device key. The camera sends its cameraId; we issue a
          // fresh nonce and it must sign it (verified in the camera-auth case).
          const cam = msg.cameraId ? auth.getCamera(msg.cameraId) : null;
          if (cam && !cam.revoked) {
            const nonce = crypto.randomBytes(32).toString('base64');
            peer.cameraChallenge = { cameraId: msg.cameraId, nonce };
            send(ws, { type: 'camera-challenge', nonce });
            return; // await camera-auth
          }
          // Legacy fallback: shared CAMERA_TOKEN (camera simulator / migration).
          if (ALLOW_CAMERA_TOKEN && msg.token && msg.token === CAMERA_TOKEN) {
            finalizeCameraRegister(peer, 'shared-token');
            return;
          }
          auth.record('camera_reject', { cameraId: msg.cameraId || null, ip: peer.ip });
          send(ws, { type: 'error', code: 'bad_token', message: 'unknown or unverified camera' });
          ws.close();
          return;
        }

        // Viewer: per-viewer access JWT preferred; legacy shared token is a
        // fallback only while VIEWER_TOKEN is still configured.
        let identity = null;
        if (msg.accessToken) {
          identity = auth.authenticateViewer(msg.accessToken);
        } else if (VIEWER_TOKEN && msg.token === VIEWER_TOKEN) {
          identity = { id: 'legacy', name: 'shared-token' };
        }
        if (!identity) {
          send(ws, { type: 'error', code: 'bad_token', message: 'invalid or expired credentials' });
          ws.close();
          return;
        }
        peer.viewer = identity;
        clearTimeout(peer.authTimer);
        peer.role = 'viewer';
        clients.set(peer.id, peer);
        send(ws, {
          type: 'welcome',
          id: peer.id,
          role: 'viewer',
          cameraId: CAMERA_ID,
          iceServers: iceServers(),
          cameraOnline: !!camera,
          vapidPublicKey: vapidPublicKey(),
        });
        if (camera) send(camera.ws, { type: 'peer-joined', id: peer.id });
        auth.record('viewer_connect', { viewerId: peer.viewer.id, name: peer.viewer.name, ip: peer.ip });
        console.log(`[+] viewer ${peer.id} (${peer.viewer.name}) registered (${clients.size} clients)`);
        return;
      }

      // Camera proves possession of its private key by signing the nonce.
      case 'camera-auth': {
        const ch = peer.cameraChallenge;
        if (!ch) { send(ws, { type: 'error', code: 'no_challenge' }); return; }
        if (auth.verifyCameraSignature(ch.cameraId, ch.nonce, msg.signature)) {
          peer.cameraChallenge = null;
          finalizeCameraRegister(peer, ch.cameraId);
        } else {
          auth.record('camera_auth_fail', { cameraId: ch.cameraId, ip: peer.ip });
          send(ws, { type: 'error', code: 'bad_signature', message: 'camera signature invalid' });
          ws.close();
        }
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
    } catch (err) {
      console.warn('[ws] handler error:', err?.message);
      try { ws.close(); } catch {}
    }
  });

  ws.on('close', () => {
    clearTimeout(peer.authTimer);
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
    if (peer.role === 'viewer' && peer.viewer) {
      auth.record('viewer_disconnect', { viewerId: peer.viewer.id, name: peer.viewer.name });
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
  console.log(`  auth=${authState.enabled
    ? `per-viewer enabled (${authState.viewers} viewers)${VIEWER_TOKEN ? ' + legacy token' : ''}`
    : VIEWER_TOKEN ? 'shared VIEWER_TOKEN only (set JWT_SECRET + ADMIN_PASSWORD for per-viewer)' : 'NONE — set JWT_SECRET+ADMIN_PASSWORD or VIEWER_TOKEN'}`);
  if (authState.enabled) console.log(`  admin console:   http://localhost:${PORT}/admin.html`);
});
