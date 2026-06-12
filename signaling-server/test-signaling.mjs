// Quick end-to-end test of the signaling broker. Run with the server up:
//   PORT=8080 CAMERA_TOKEN=cam-test VIEWER_TOKEN=view-test node server.js
//   node test-signaling.mjs
import { WebSocket } from 'ws';

const URL = process.env.URL || 'ws://localhost:8080';
const CAM = process.env.CAMERA_TOKEN || 'cam-test';
const VIEW = process.env.VIEWER_TOKEN || 'view-test';

let passed = 0, failed = 0;
const ok = (c, m) => { (c ? passed++ : failed++); console.log(`${c ? 'PASS' : 'FAIL'}  ${m}`); };
const open = (ws) => new Promise((r) => ws.once('open', r));
const next = (ws) => new Promise((r) => ws.once('message', (d) => r(JSON.parse(d.toString()))));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 1. Bad token is rejected.
{
  const ws = new WebSocket(URL);
  await open(ws);
  ws.send(JSON.stringify({ type: 'register', role: 'camera', token: 'wrong' }));
  const m = await next(ws);
  ok(m.type === 'error' && m.code === 'bad_token', 'bad token rejected');
  ws.close();
}

// 2. Camera registers, viewer registers, relay works.
const cam = new WebSocket(URL);
await open(cam);
cam.send(JSON.stringify({ type: 'register', role: 'camera', token: CAM }));
const camWelcome = await next(cam);
ok(camWelcome.type === 'welcome' && camWelcome.role === 'camera', 'camera welcome');
ok(Array.isArray(camWelcome.iceServers) && camWelcome.iceServers.length > 0, 'camera got iceServers');

const viewer = new WebSocket(URL);
await open(viewer);
viewer.send(JSON.stringify({ type: 'register', role: 'viewer', token: VIEW }));
const viewWelcome = await next(viewer);
ok(viewWelcome.type === 'welcome' && viewWelcome.cameraOnline === true, 'viewer welcome, camera online');
const viewerId = viewWelcome.id;

// Camera should be told a peer joined.
const peerJoined = await next(cam);
ok(peerJoined.type === 'peer-joined' && peerJoined.id === viewerId, 'camera notified peer-joined');

// 3. Viewer offer -> camera receives with from=viewerId.
viewer.send(JSON.stringify({ type: 'offer', sdp: 'FAKE_OFFER' }));
const offer = await next(cam);
ok(offer.type === 'offer' && offer.sdp === 'FAKE_OFFER' && offer.from === viewerId, 'offer relayed to camera');

// 4. Camera answer -> viewer receives.
cam.send(JSON.stringify({ type: 'answer', to: viewerId, sdp: 'FAKE_ANSWER' }));
const answer = await next(viewer);
ok(answer.type === 'answer' && answer.sdp === 'FAKE_ANSWER', 'answer relayed to viewer');

// 5. ICE both ways.
viewer.send(JSON.stringify({ type: 'ice', candidate: { c: 'v' } }));
const iceAtCam = await next(cam);
ok(iceAtCam.type === 'ice' && iceAtCam.candidate.c === 'v', 'ice viewer->camera');
cam.send(JSON.stringify({ type: 'ice', to: viewerId, candidate: { c: 'c' } }));
const iceAtViewer = await next(viewer);
ok(iceAtViewer.type === 'ice' && iceAtViewer.candidate.c === 'c', 'ice camera->viewer');

// 6. Motion broadcast.
cam.send(JSON.stringify({ type: 'motion', level: 42, ts: 123 }));
const motion = await next(viewer);
ok(motion.type === 'motion' && motion.level === 42, 'motion broadcast to viewer');

// 7. Camera offline notifies viewer.
cam.close();
const offlineMsg = await next(viewer);
ok(offlineMsg.type === 'camera-offline', 'viewer notified camera-offline');

await sleep(50);
viewer.close();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
