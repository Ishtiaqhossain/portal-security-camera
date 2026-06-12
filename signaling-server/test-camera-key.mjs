// Tests per-device camera identity (EC P-256 challenge-response), camera
// revocation, the legacy-token fallback, admin-login rate-limiting, and security
// headers. Run with the server up (CAMERA_TOKEN + JWT_SECRET + ADMIN_PASSWORD set).
import { WebSocket } from 'ws';
import crypto from 'node:crypto';

const BASE = process.env.BASE || 'http://localhost:8080';
const WS_URL = BASE.replace(/^http/, 'ws');
const CAMERA_TOKEN = process.env.CAMERA_TOKEN || 'cam-test';
const ADMIN_PW = process.env.ADMIN_PASSWORD || 'test-admin-pw';

let passed = 0, failed = 0;
const ok = (c, m) => { (c ? passed++ : failed++); console.log(`${c ? 'PASS' : 'FAIL'}  ${m}`); };
const post = (path, body, token) => fetch(BASE + path, {
  method: 'POST',
  headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  body: JSON.stringify(body || {}),
});

// A camera key pair (private key never leaves the device in real life).
const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const publicKeyB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const sign = (nonceB64, key = privateKey) =>
  crypto.sign('sha256', Buffer.from(nonceB64, 'base64'), key).toString('base64');

// Drive a camera registration; resolves with the final server message.
function cameraRegister({ cameraId, token, signer }) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    ws.on('open', () => ws.send(JSON.stringify({ type: 'register', role: 'camera', cameraId, token })));
    ws.on('message', (d) => {
      const m = JSON.parse(d.toString());
      if (m.type === 'camera-challenge') {
        ws.send(JSON.stringify({ type: 'camera-auth', signature: signer(m.nonce) }));
        return; // wait for welcome/error
      }
      ws.close();
      resolve(m);
    });
    ws.on('error', () => resolve({ type: 'error', code: 'ws_error' }));
    setTimeout(() => { try { ws.close(); } catch {} resolve({ type: 'timeout' }); }, 3000);
  });
}

// 1. Provision a camera with its public key (bootstrap via CAMERA_TOKEN).
const prov = await post('/camera/provision', { name: 'Front door', publicKey: publicKeyB64 }, CAMERA_TOKEN);
ok(prov.status === 200, 'camera provisioned with public key');
const { id: cameraId } = await prov.json();
ok(typeof cameraId === 'string', 'got a cameraId');

// 2. Provision rejects a non-key.
ok((await post('/camera/provision', { name: 'x', publicKey: 'not-a-key' }, CAMERA_TOKEN)).status === 400, 'provision rejects garbage key');

// 3. Challenge-response with the real key -> welcome.
ok((await cameraRegister({ cameraId, signer: (n) => sign(n) })).type === 'welcome', 'camera authenticates by signing the nonce');

// 4. A wrong key fails the challenge.
const wrong = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey;
ok((await cameraRegister({ cameraId, signer: (n) => sign(n, wrong) })).code === 'bad_signature', 'wrong key is rejected (bad_signature)');

// 5. Legacy CAMERA_TOKEN still works (simulator/migration).
ok((await cameraRegister({ token: CAMERA_TOKEN, signer: () => '' })).type === 'welcome', 'legacy CAMERA_TOKEN still registers');

// 6. Admin can list + revoke the camera; revoked camera can't auth.
const adminToken = (await (await post('/auth/admin', { password: ADMIN_PW })).json()).adminToken;
const cams = (await (await fetch(BASE + '/admin/cameras', { headers: { authorization: `Bearer ${adminToken}` } })).json()).cameras;
ok(cams.some((c) => c.id === cameraId), 'admin lists the camera');
ok((await post('/admin/camera-revoke', { id: cameraId }, adminToken)).status === 200, 'admin revoked the camera');
ok((await cameraRegister({ cameraId, signer: (n) => sign(n) })).code === 'bad_token', 'revoked camera cannot authenticate');

// 7. Security headers present.
const h = (await fetch(BASE + '/healthz')).headers;
ok(h.get('x-content-type-options') === 'nosniff' && h.get('content-security-policy')?.includes('frame-ancestors'),
  'security headers present (nosniff + frame-ancestors)');

// 8. Admin login rate-limit: lock out after repeated failures.
let got429 = false;
for (let i = 0; i < 7; i++) {
  const r = await post('/auth/admin', { password: 'wrong' });
  if (r.status === 429) { got429 = true; break; }
}
ok(got429, 'admin login is rate-limited / locked out after repeated failures');

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
