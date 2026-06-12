// Portal Security — camera simulator.
//
// A browser stand-in for the Portal camera agent, using this computer's webcam.
// It implements the SAME contract the Android app must implement:
//   - register as role "camera"
//   - one RTCPeerConnection per viewer
//   - the camera is the ANSWERER (viewer offers, camera answers)
//   - add local video (sendonly to viewer) + mic (sendrecv) tracks
//   - receive and play the viewer's talk-back audio
//   - run frame-differencing motion detection and emit "motion" alerts
//
// Use it to validate the server + viewer before the device is in hand, and as a
// reference while porting the logic to Kotlin/WebRTC on Android.

const $ = (id) => document.getElementById(id);
const ui = {
  status: $('status'),
  connectCard: $('connect-card'),
  connectError: $('connect-error'),
  server: $('server'),
  token: $('token'),
  startBtn: $('start-btn'),
  view: $('view'),
  local: $('local'),
  viewerCount: $('viewer-count'),
  motionBtn: $('motion-btn'),
  autoMotionBtn: $('auto-motion-btn'),
  motionStatus: $('motion-status'),
  stopBtn: $('stop-btn'),
  canvas: $('motion-canvas'),
};

const state = {
  ws: null,
  localStream: null,
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
  peers: new Map(), // viewerId -> RTCPeerConnection
  autoMotion: true,
};

function wsUrl() {
  const explicit = ui.server.value.trim();
  if (explicit) return explicit.replace(/^http/, 'ws');
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}`;
}
function setStatus(t, c = '') { ui.status.textContent = t; ui.status.className = `status ${c}`; }
function send(obj) { if (state.ws?.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(obj)); }

// --- Start / stop ------------------------------------------------------------

async function start() {
  if (!ui.token.value.trim()) { ui.connectError.textContent = 'Enter the camera token.'; return; }
  ui.connectError.textContent = '';
  setStatus('starting camera…');

  try {
    state.localStream = await navigator.mediaDevices.getUserMedia({
      video: { width: 1280, height: 720 },
      audio: { echoCancellation: true, noiseSuppression: true },
    });
  } catch (e) {
    ui.connectError.textContent = `Camera/mic access denied: ${e.message}`;
    setStatus('camera error', 'error');
    return;
  }
  ui.local.srcObject = state.localStream;
  ui.connectCard.classList.add('hidden');
  ui.view.classList.remove('hidden');
  startMotionDetection();
  connect();
}

function stop() {
  const ws = state.ws;
  state.ws = null;
  if (ws) { try { ws.send(JSON.stringify({ type: 'bye' })); } catch {} ws.close(); }
  for (const pc of state.peers.values()) pc.close();
  state.peers.clear();
  updateViewerCount();
  state.localStream?.getTracks().forEach((t) => t.stop());
  state.localStream = null;
  stopMotionDetection();
  ui.view.classList.add('hidden');
  ui.connectCard.classList.remove('hidden');
  setStatus('stopped');
}

// --- Signaling ---------------------------------------------------------------

function connect() {
  const ws = new WebSocket(wsUrl());
  state.ws = ws;
  ws.onopen = () => ws.send(JSON.stringify({ type: 'register', role: 'camera', token: ui.token.value.trim() }));
  ws.onmessage = async (ev) => {
    const msg = JSON.parse(ev.data);
    switch (msg.type) {
      case 'welcome':
        state.iceServers = msg.iceServers || state.iceServers;
        setStatus('online', 'connected');
        break;
      case 'peer-joined':
        // Viewer will send us an offer; nothing to do yet.
        break;
      case 'peer-left':
        closePeer(msg.id);
        break;
      case 'offer':
        await onOffer(msg.from, msg.sdp);
        break;
      case 'ice':
        if (msg.from && state.peers.has(msg.from) && msg.candidate) {
          try { await state.peers.get(msg.from).addIceCandidate(msg.candidate); } catch (e) { console.warn(e); }
        }
        break;
      case 'error':
        console.warn('server error', msg);
        if (msg.code === 'bad_token') { ui.connectError.textContent = 'Invalid camera token.'; stop(); }
        break;
    }
  };
  ws.onclose = () => {
    setStatus('disconnected', 'error');
    if (state.ws === ws) setTimeout(() => { if (state.localStream) connect(); }, 3000);
  };
}

async function onOffer(viewerId, sdp) {
  closePeer(viewerId);
  const pc = new RTCPeerConnection({ iceServers: state.iceServers });
  state.peers.set(viewerId, pc);
  updateViewerCount();

  // Send our camera + mic to the viewer.
  for (const track of state.localStream.getTracks()) pc.addTrack(track, state.localStream);

  // Play the viewer's talk-back audio.
  pc.ontrack = (ev) => {
    let audio = document.getElementById(`a-${viewerId}`);
    if (!audio) {
      audio = document.createElement('audio');
      audio.id = `a-${viewerId}`;
      audio.autoplay = true;
      document.body.appendChild(audio);
    }
    audio.srcObject = ev.streams[0];
  };

  pc.onicecandidate = (ev) => { if (ev.candidate) send({ type: 'ice', to: viewerId, candidate: ev.candidate }); };
  pc.onconnectionstatechange = () => {
    if (['failed', 'closed', 'disconnected'].includes(pc.connectionState)) closePeer(viewerId);
  };

  await pc.setRemoteDescription({ type: 'offer', sdp });
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  send({ type: 'answer', to: viewerId, sdp: answer.sdp });
}

function closePeer(viewerId) {
  const pc = state.peers.get(viewerId);
  if (pc) { pc.close(); state.peers.delete(viewerId); }
  document.getElementById(`a-${viewerId}`)?.remove();
  updateViewerCount();
}

function updateViewerCount() { ui.viewerCount.textContent = String(state.peers.size); }

// --- Motion detection (luma frame differencing) ------------------------------
//
// Downscale each frame to 64x48, compare luma to the previous frame, count the
// fraction of pixels that changed beyond a threshold. This is the same approach
// the Android agent uses on WebRTC video frames.

let motionTimer = null;
let prevLuma = null;
let lastAlert = 0;
const ctx = ui.canvas.getContext('2d', { willReadFrequently: true });

function startMotionDetection() {
  prevLuma = null;
  motionTimer = setInterval(sampleMotion, 200); // 5 fps is plenty
}
function stopMotionDetection() { clearInterval(motionTimer); motionTimer = null; ui.motionStatus.textContent = 'motion detector idle'; }

function sampleMotion() {
  const v = ui.local;
  if (!v.videoWidth) return;
  ctx.drawImage(v, 0, 0, ui.canvas.width, ui.canvas.height);
  const { data } = ctx.getImageData(0, 0, ui.canvas.width, ui.canvas.height);
  const luma = new Uint8ClampedArray(ui.canvas.width * ui.canvas.height);
  for (let i = 0, p = 0; i < data.length; i += 4, p++) {
    luma[p] = (data[i] * 77 + data[i + 1] * 150 + data[i + 2] * 29) >> 8; // 0.299/0.587/0.114
  }
  if (prevLuma) {
    let changed = 0;
    const PIXEL_DELTA = 25;
    for (let p = 0; p < luma.length; p++) {
      if (Math.abs(luma[p] - prevLuma[p]) > PIXEL_DELTA) changed++;
    }
    const fraction = changed / luma.length;
    const level = Math.min(100, Math.round(fraction * 100));
    ui.motionStatus.textContent = `motion level: ${level}`;
    // Trigger when >4% of pixels change, rate-limited to once per 5s.
    if (fraction > 0.04 && state.autoMotion && Date.now() - lastAlert > 5000) {
      lastAlert = Date.now();
      emitMotion(level);
    }
  }
  prevLuma = luma;
}

function emitMotion(level) {
  send({ type: 'motion', level, ts: Date.now() });
  ui.motionStatus.textContent = `⚠ motion alert sent (level ${level})`;
}

// --- Wiring ------------------------------------------------------------------

ui.startBtn.onclick = start;
ui.stopBtn.onclick = stop;
ui.motionBtn.onclick = () => emitMotion(100);
ui.autoMotionBtn.onclick = () => {
  state.autoMotion = !state.autoMotion;
  ui.autoMotionBtn.setAttribute('aria-pressed', String(state.autoMotion));
  ui.autoMotionBtn.textContent = `👁 Auto motion: ${state.autoMotion ? 'on' : 'off'}`;
};

// Restore saved server URL for convenience.
ui.server.value = JSON.parse(localStorage.getItem('portal-cam') || '{}').server || '';
ui.server.onchange = () => localStorage.setItem('portal-cam', JSON.stringify({ server: ui.server.value }));
