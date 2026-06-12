// Per-viewer authentication for the Portal security camera.
//
// Replaces the single shared VIEWER_TOKEN with real identities:
//   - The owner (admin) logs in with ADMIN_PASSWORD and gets a short admin JWT.
//   - The owner creates a named invite; the invite link is redeemed once by a
//     viewer, which provisions a viewer identity and issues that viewer a
//     long-lived REFRESH token + a short-lived ACCESS token (both HS256 JWTs).
//   - To watch, a viewer presents its short-lived ACCESS token over the
//     WebSocket. The server verifies the signature + expiry AND that the viewer
//     is not revoked, so revocation takes effect on the next (re)connect; live
//     sessions are kicked immediately by the server.
//
// Security choices:
//   - JWTs are HS256 and the verifier PINS alg=HS256 (blocks alg-confusion/none).
//   - Signature comparison is constant-time.
//   - Access tokens are short (15m) so a leak is self-limiting; refresh tokens
//     are revocable per-viewer; nothing long-lived rides in URLs.

import crypto from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const dataDir = process.env.DATA_DIR || __dirname;
const STORE = join(dataDir, 'viewers.json');
const AUDIT = join(dataDir, 'audit.json');

const ACCESS_TTL = Number(process.env.ACCESS_TTL || 15 * 60);          // 15 min
const REFRESH_TTL = Number(process.env.REFRESH_TTL || 30 * 24 * 3600); // 30 days
const ADMIN_TTL = Number(process.env.ADMIN_TTL || 2 * 3600);           // 2 hours
const ENROLL_TTL = Number(process.env.ENROLL_TTL || 120);              // 2 min QR
const SAME_NETWORK = process.env.ENROLL_SAME_NETWORK !== 'false';
const AUDIT_MAX = 1000;

// Short-lived enrollment tickets live in memory only (not persisted).
const tickets = new Map(); // token -> { name, ip, expiresAt }

let secret = '';
let adminPassword = '';
let db = { viewers: [], invites: [] };
let audit = [];

export function initAuth() {
  secret = process.env.JWT_SECRET || '';
  adminPassword = process.env.ADMIN_PASSWORD || '';
  load();
  const enabled = secret.length > 0 && adminPassword.length > 0;
  return { enabled, viewers: db.viewers.length };
}

export function isEnabled() {
  return secret.length > 0 && adminPassword.length > 0;
}

// --- JWT (HS256) ------------------------------------------------------------

function b64url(input) {
  return Buffer.from(input).toString('base64url');
}

function signJwt(payload, ttlSec) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify({ ...payload, iat: now, exp: now + ttlSec }));
  const data = `${header}.${body}`;
  const sig = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  return `${data}.${sig}`;
}

function verifyJwt(token) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [h, p, sig] = parts;
  const data = `${h}.${p}`;
  const expected = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  let header, payload;
  try {
    header = JSON.parse(Buffer.from(h, 'base64url').toString());
    payload = JSON.parse(Buffer.from(p, 'base64url').toString());
  } catch {
    return null;
  }
  if (header.alg !== 'HS256') return null; // pin algorithm
  if (!payload.exp || Math.floor(Date.now() / 1000) >= payload.exp) return null;
  return payload;
}

// --- Admin ------------------------------------------------------------------

export function adminLogin(password) {
  if (!adminPassword) return null;
  const a = Buffer.from(String(password));
  const b = Buffer.from(adminPassword);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  return signJwt({ role: 'admin' }, ADMIN_TTL);
}

export function requireAdmin(authorizationHeader) {
  const token = (authorizationHeader || '').replace(/^Bearer\s+/i, '');
  const payload = verifyJwt(token);
  return payload && payload.role === 'admin' ? payload : null;
}

// --- Enrollment & viewers ---------------------------------------------------

function normalizeIp(ip) {
  if (!ip) return 'unknown';
  ip = String(ip);
  if (ip === '::1' || ip === '127.0.0.1' || ip === '::ffff:127.0.0.1') return 'local';
  return ip.replace(/^::ffff:/, '');
}

// Device-initiated: the camera mints a single-use ticket bound to its own
// network. Rendered as a QR on the Portal screen.
export function createEnrollTicket(name, ip) {
  const token = crypto.randomBytes(32).toString('base64url');
  tickets.set(token, {
    name: String(name || 'New device').slice(0, 60),
    ip: normalizeIp(ip),
    expiresAt: Date.now() + ENROLL_TTL * 1000,
  });
  return { token, expiresIn: ENROLL_TTL };
}

// The viewer's phone redeems the ticket after scanning. Rejected unless the
// phone is on the same network as the camera (same public IP), so a relayed
// screenshot scanned off-network fails.
export function enroll(token, ip) {
  const ticket = tickets.get(token);
  if (!ticket || Date.now() > ticket.expiresAt) { tickets.delete(token); return { error: 'invalid' }; }
  if (SAME_NETWORK && normalizeIp(ip) !== ticket.ip) return { error: 'network' };
  tickets.delete(token); // single use
  const viewer = {
    id: crypto.randomBytes(9).toString('base64url'),
    name: ticket.name,
    createdAt: Date.now(),
    revoked: false,
    lastSeenAt: null,
  };
  db.viewers.push(viewer);
  persist();
  return {
    viewer: publicViewer(viewer),
    refreshToken: signJwt({ sub: viewer.id, kind: 'refresh' }, REFRESH_TTL),
    accessToken: signJwt({ sub: viewer.id, name: viewer.name, kind: 'access' }, ACCESS_TTL),
  };
}

/** Exchange a refresh token for a fresh access token (denied if revoked). */
export function refresh(refreshToken) {
  const payload = verifyJwt(refreshToken);
  if (!payload || payload.kind !== 'refresh') return null;
  const viewer = db.viewers.find((v) => v.id === payload.sub);
  if (!viewer || viewer.revoked) return null;
  return {
    viewer: publicViewer(viewer),
    accessToken: signJwt({ sub: viewer.id, name: viewer.name, kind: 'access' }, ACCESS_TTL),
  };
}

/** Verify a viewer's access token at WebSocket connect time. */
export function authenticateViewer(accessToken) {
  const payload = verifyJwt(accessToken);
  if (!payload || payload.kind !== 'access') return null;
  const viewer = db.viewers.find((v) => v.id === payload.sub);
  if (!viewer || viewer.revoked) return null;
  viewer.lastSeenAt = Date.now();
  persist();
  return { id: viewer.id, name: viewer.name };
}

export function listViewers() {
  return db.viewers.map(publicViewer);
}

export function setRevoked(id, revoked) {
  const viewer = db.viewers.find((v) => v.id === id);
  if (!viewer) return null;
  viewer.revoked = revoked;
  persist();
  return publicViewer(viewer);
}

function publicViewer(v) {
  return { id: v.id, name: v.name, createdAt: v.createdAt, lastSeenAt: v.lastSeenAt, revoked: v.revoked };
}

// --- Audit ------------------------------------------------------------------

export function record(event, data) {
  audit.push({ ts: Date.now(), event, ...data });
  if (audit.length > AUDIT_MAX) audit = audit.slice(-AUDIT_MAX);
  try {
    writeFileSync(AUDIT, JSON.stringify(audit));
  } catch { /* best effort */ }
}

export function listAudit(limit = 200) {
  return audit.slice(-limit).reverse();
}

// --- Persistence ------------------------------------------------------------

function load() {
  if (existsSync(STORE)) {
    try { db = JSON.parse(readFileSync(STORE, 'utf8')); } catch { db = { viewers: [], invites: [] }; }
  }
  if (!db.viewers) db.viewers = [];
  if (!db.invites) db.invites = [];
  if (existsSync(AUDIT)) {
    try { audit = JSON.parse(readFileSync(AUDIT, 'utf8')); } catch { audit = []; }
  }
}

function persist() {
  try { writeFileSync(STORE, JSON.stringify(db, null, 2)); }
  catch (e) { console.warn('[auth] persist failed:', e.message); }
}
