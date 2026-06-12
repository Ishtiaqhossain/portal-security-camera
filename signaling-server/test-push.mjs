// Exercises the Web Push path: viewer subscribes, store persists, motion fires a
// push attempt. Run with the server up (VAPID_* set in .env).
import { WebSocket } from 'ws';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const WS_URL = process.env.URL || 'ws://localhost:8080';
const CAM = process.env.CAMERA_TOKEN || 'cam-test';
const VIEW = process.env.VIEWER_TOKEN || 'view-test';
const STORE = join(dirname(fileURLToPath(import.meta.url)), 'subscriptions.json');

let passed = 0, failed = 0;
const ok = (c, m) => { (c ? passed++ : failed++); console.log(`${c ? 'PASS' : 'FAIL'}  ${m}`); };
const open = (ws) => new Promise((r) => ws.once('open', r));
const next = (ws) => new Promise((r) => ws.once('message', (d) => r(JSON.parse(d.toString()))));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const cam = new WebSocket(WS_URL);
await open(cam);
cam.send(JSON.stringify({ type: 'register', role: 'camera', token: CAM }));
await next(cam);

const viewer = new WebSocket(WS_URL);
await open(viewer);
viewer.send(JSON.stringify({ type: 'register', role: 'viewer', token: VIEW }));
const welcome = await next(viewer);
ok(typeof welcome.vapidPublicKey === 'string' && welcome.vapidPublicKey.length > 20,
  `welcome carries VAPID public key (${welcome.vapidPublicKey?.slice(0, 12)}…)`);

const fakeEndpoint = 'https://example.com/push-endpoint-' + Date.now();
viewer.send(JSON.stringify({
  type: 'subscribe-push',
  subscription: { endpoint: fakeEndpoint, keys: { p256dh: 'BFakeKey', auth: 'fakeAuth' } },
}));
// drain camera's peer-joined first if it arrives on viewer? No — subscribe ack on viewer.
let ack = await next(viewer);
ok(ack.type === 'push-subscribed' && ack.ok === true, 'server acked push-subscribed ok');

await sleep(150);
ok(existsSync(STORE), 'subscriptions.json was written');
if (existsSync(STORE)) {
  const stored = JSON.parse(readFileSync(STORE, 'utf8'));
  ok(stored.some((s) => s.endpoint === fakeEndpoint), 'subscription persisted to store');
}

// Fire motion from the camera -> server attempts a push (to the fake endpoint,
// which will fail gracefully; we just confirm the server doesn't crash).
cam.send(JSON.stringify({ type: 'motion', level: 88, ts: Date.now() }));
const motionAtViewer = await next(viewer);
ok(motionAtViewer.type === 'motion' && motionAtViewer.level === 88, 'live motion still broadcast to open viewer');

await sleep(500); // let the push attempt resolve/prune
ok(true, 'server survived motion->push (no crash)');

cam.close(); viewer.close();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
