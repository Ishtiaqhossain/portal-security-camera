// End-to-end test of device-initiated enrollment + same-network check.
// Run with the server up (JWT_SECRET + ADMIN_PASSWORD + CAMERA_TOKEN set):
//   node server.js & ; node test-enroll.mjs
import { WebSocket } from 'ws';

const BASE = process.env.BASE || 'http://localhost:8080';
const WS_URL = BASE.replace(/^http/, 'ws');
const CAMERA_TOKEN = process.env.CAMERA_TOKEN || 'cam-test';
const ADMIN_PW = process.env.ADMIN_PASSWORD || 'test-admin-pw';

let passed = 0, failed = 0;
const ok = (c, m) => { (c ? passed++ : failed++); console.log(`${c ? 'PASS' : 'FAIL'}  ${m}`); };
const req = (path, { method = 'GET', body, token, xff } = {}) => fetch(BASE + path, {
  method,
  headers: {
    ...(body ? { 'content-type': 'application/json' } : {}),
    ...(token ? { authorization: `Bearer ${token}` } : {}),
    ...(xff ? { 'x-forwarded-for': xff } : {}),
  },
  body: body ? JSON.stringify(body) : undefined,
});
const wsRegister = (msg) => new Promise((resolve) => {
  const ws = new WebSocket(WS_URL);
  ws.on('open', () => ws.send(JSON.stringify({ type: 'register', role: 'viewer', ...msg })));
  ws.on('message', (d) => { const m = JSON.parse(d.toString()); ws.close(); resolve(m); });
  ws.on('error', () => resolve({ type: 'error', code: 'ws_error' }));
});

// 1. Camera endpoints require the camera token.
ok((await req('/camera/viewers')).status === 401, 'camera endpoints reject missing token');
ok((await req('/camera/viewers', { token: 'wrong-token-same-len-as-real' })).status === 401, 'camera endpoints reject wrong token');

// 2. Camera mints an enrollment ticket (bound to its IP — localhost here).
const ticketRes = await req('/camera/enroll/start', { method: 'POST', body: { name: "Mom's iPhone" }, token: CAMERA_TOKEN });
ok(ticketRes.status === 200, 'camera created an enrollment ticket');
const { token } = await ticketRes.json();
ok(typeof token === 'string' && token.length > 30, 'ticket carries a high-entropy token');

// 3. Enroll from a DIFFERENT network (forged X-Forwarded-For) -> rejected.
const offNet = await req('/auth/enroll', { method: 'POST', body: { token }, xff: '203.0.113.7' });
ok(offNet.status === 400 && (await offNet.json()).reason === 'network', 'off-network enrollment rejected (same-Wi-Fi check)');

// 4. Enroll from the same network (localhost, like the camera) -> success.
const enrolled = await (await req('/auth/enroll', { method: 'POST', body: { token } })).json();
ok(enrolled.accessToken && enrolled.refreshToken && enrolled.viewer?.name === "Mom's iPhone",
  'same-network enrollment issues tokens + viewer');
const { accessToken, refreshToken, viewer } = enrolled;

// 5. Ticket is single-use.
ok((await req('/auth/enroll', { method: 'POST', body: { token } })).status === 400, 'ticket cannot be reused');

// 6. Enrolled device connects over WS.
ok((await wsRegister({ accessToken })).type === 'welcome', 'enrolled device connects (valid access token)');
ok((await wsRegister({ accessToken: 'garbage' })).code === 'bad_token', 'bad token rejected');

// 7. Camera lists + revokes its viewers.
const list = (await (await req('/camera/viewers', { token: CAMERA_TOKEN })).json()).viewers;
ok(list.some((v) => v.id === viewer.id && !v.revoked), 'camera lists the enrolled viewer');
ok((await req('/camera/revoke', { method: 'POST', body: { id: viewer.id }, token: CAMERA_TOKEN })).status === 200, 'camera revoked the viewer');
ok((await req('/auth/refresh', { method: 'POST', body: { refreshToken } })).status === 401, 'refresh denied after revoke');
ok((await wsRegister({ accessToken })).code === 'bad_token', 'connect denied after revoke (unexpired token)');

// 8. Re-enable restores access.
ok((await req('/camera/enable', { method: 'POST', body: { id: viewer.id }, token: CAMERA_TOKEN })).status === 200, 'camera re-enabled the viewer');
const reRefresh = await (await req('/auth/refresh', { method: 'POST', body: { refreshToken } })).json();
ok((await wsRegister({ accessToken: reRefresh.accessToken })).type === 'welcome', 'access restored after re-enable');

// 9. Admin console (web) still works for read/revoke.
const adminToken = (await (await req('/auth/admin', { method: 'POST', body: { password: ADMIN_PW } })).json()).adminToken;
ok(typeof adminToken === 'string', 'admin login works');
const audit = (await (await req('/admin/audit', { token: adminToken })).json()).audit;
ok(audit.some((e) => e.event === 'enroll') && audit.some((e) => e.event === 'revoke'), 'audit captured enroll + revoke');

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
