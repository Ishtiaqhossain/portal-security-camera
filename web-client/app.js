// Portal Security — browser viewer.
//
// Connects to the signaling server, negotiates a WebRTC session with the Portal
// camera agent, shows the live feed, supports talk-back (two-way audio), and
// surfaces motion alerts.
//
// Negotiation role: the VIEWER is the offerer. It offers `video: recvonly`
// (receive the camera) and `audio: sendrecv` (send mic for talk-back, receive
// the camera's mic). The camera answers. Keep this contract identical to the
// camera agent (camera-sim.js / the Android app).

const $ = (id) => document.getElementById(id);

// Surface any uncaught error/rejection in the status bar (debug aid).
function showFatal(text) {
  const s = document.getElementById('status');
  if (s) { s.textContent = text; s.className = 'status error'; }
}
window.addEventListener('error', (ev) => { if (ev.error) showFatal('JS error: ' + (ev.error.message || ev.message)); });
window.addEventListener('unhandledrejection', (ev) => showFatal('error: ' + (ev.reason?.message || ev.reason)));

const ui = {
  status: $('status'),
  connectCard: $('connect-card'),
  connectError: $('connect-error'),
  server: $('server'),
  token: $('token'),
  connectBtn: $('connect-btn'),
  view: $('view'),
  remote: $('remote'),
  liveDot: $('live-dot'),
  liveLabel: $('live-label'),
  micBtn: $('mic-btn'),
  muteAudioBtn: $('mute-audio-btn'),
  fullscreenBtn: $('fullscreen-btn'),
  notifyBtn: $('notify-btn'),
  disconnectBtn: $('disconnect-btn'),
  toasts: $('toasts'),
  authMessage: $('auth-message'),
  legacyAuth: $('legacy-auth'),
};

const state = {
  ws: null,
  pc: null,
  micStream: null,
  micEnabled: false,
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
  reconnectTimer: null,
  vapidPublicKey: null,
  swReg: null,
  authEnabled: false,
  accessToken: null,
};

function httpBase() {
  const explicit = ui.server.value.trim();
  if (explicit) return explicit.replace(/^ws/, 'http');
  return location.origin;
}

// The viewer is always served by the signaling server, so the API + WebSocket
// live at this page's own origin. (Kept overridable above only if a server is
// explicitly typed, but default to same-origin to avoid stale-value bugs.)

// --- Init: detect auth mode and choose the right entry flow -----------------

async function init() {
  registerServiceWorker();
  refreshNotifyButton();
  const saved = JSON.parse(localStorage.getItem('portal-viewer') || '{}');
  // Same-origin by default. Ignore any stale saved "server" value, which
  // previously made /auth/config fetch the wrong host and silently fail.
  ui.server.value = new URLSearchParams(location.search).get('server') ?? '';

  setStatus('checking access…');
  let cfg = {};
  try {
    cfg = await (await fetch(httpBase() + '/auth/config')).json();
  } catch (e) {
    setStatus('cannot reach ' + httpBase() + ' — ' + (e?.message || e), 'error');
    return;
  }
  state.authEnabled = !!cfg.authEnabled;

  if (state.authEnabled) {
    // Per-viewer mode: no token field. Auto-connect if this device was invited.
    ui.legacyAuth.classList.add('hidden');
    if (localStorage.getItem('portal-refresh')) {
      ui.connectBtn.textContent = 'Watch';
      ui.connectBtn.classList.remove('hidden');
      connect();
    } else {
      ui.authMessage.classList.remove('hidden');
      ui.connectBtn.classList.add('hidden');
      setStatus('not enrolled on this device', 'error');
    }
  } else {
    // Legacy shared-token mode.
    ui.legacyAuth.classList.remove('hidden');
    ui.connectBtn.textContent = 'Connect';
    ui.connectBtn.classList.remove('hidden');
    const q = new URLSearchParams(location.search);
    ui.token.value = q.get('token') ?? saved.token ?? '';
    if (q.get('token')) connect();
  }
}

// Exchange the stored refresh token for a short-lived access token.
async function getAccessToken() {
  const refreshToken = localStorage.getItem('portal-refresh');
  if (!refreshToken) return null;
  try {
    const res = await fetch(httpBase() + '/auth/refresh', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) return null;
    return (await res.json()).accessToken;
  } catch {
    return null;
  }
}

function showNeedInvite(reason) {
  state.ws = null;
  teardownCall();
  ui.view.classList.add('hidden');
  ui.connectCard.classList.remove('hidden');
  ui.legacyAuth.classList.add('hidden');
  ui.authMessage.classList.remove('hidden');
  ui.connectBtn.classList.add('hidden');
  setStatus(reason || 'access required', 'error');
}

function saveConfig() {
  localStorage.setItem('portal-viewer', JSON.stringify({ server: ui.server.value, token: ui.token.value }));
}

function wsUrl() {
  const explicit = ui.server.value.trim();
  if (explicit) return explicit.replace(/^http/, 'ws');
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}`;
}

// --- UI helpers --------------------------------------------------------------

function setStatus(text, cls = '') {
  ui.status.textContent = text;
  ui.status.className = `status ${cls}`;
}

function setLive(isLive) {
  ui.liveDot.className = `dot ${isLive ? 'live' : ''}`;
  ui.liveLabel.textContent = isLive ? 'LIVE' : 'waiting…';
}

function showToast(title, time) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.innerHTML = `<div class="t-title">${title}</div><div class="t-time">${time}</div>`;
  ui.toasts.appendChild(el);
  setTimeout(() => el.remove(), 8000);
}

// --- Connection --------------------------------------------------------------

async function connect() {
  ui.connectError.textContent = '';
  setStatus('connecting…');

  let registerMsg;
  if (state.authEnabled) {
    const accessToken = await getAccessToken();
    if (!accessToken) {
      showNeedInvite("Your access has ended or this device isn't invited.");
      return;
    }
    state.accessToken = accessToken;
    registerMsg = { type: 'register', role: 'viewer', accessToken };
    setStatus('authenticated, connecting…');
  } else {
    if (!ui.token.value.trim()) {
      ui.connectError.textContent = 'Enter a viewer token.';
      return;
    }
    saveConfig();
    registerMsg = { type: 'register', role: 'viewer', token: ui.token.value.trim() };
  }

  const ws = new WebSocket(wsUrl());
  state.ws = ws;

  ws.onopen = () => ws.send(JSON.stringify(registerMsg));

  ws.onmessage = async (ev) => {
    const msg = JSON.parse(ev.data);
    switch (msg.type) {
      case 'welcome':
        state.iceServers = msg.iceServers || state.iceServers;
        state.vapidPublicKey = msg.vapidPublicKey || null;
        refreshNotifyButton();
        setStatus('connected', 'connected');
        ui.connectCard.classList.add('hidden');
        ui.view.classList.remove('hidden');
        if (msg.cameraOnline) startCall();
        else setLive(false);
        break;
      case 'camera-online':
        startCall();
        break;
      case 'camera-offline':
        teardownCall();
        setLive(false);
        setStatus('camera offline', 'error');
        break;
      case 'answer':
        if (state.pc) await state.pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
        break;
      case 'ice':
        if (state.pc && msg.candidate) {
          try { await state.pc.addIceCandidate(msg.candidate); } catch (e) { console.warn('ice', e); }
        }
        break;
      case 'motion':
        onMotion(msg);
        break;
      case 'error':
        console.warn('server error', msg);
        if (msg.code === 'bad_token' || msg.code === 'revoked') {
          if (state.authEnabled) {
            if (msg.code === 'revoked') localStorage.removeItem('portal-refresh');
            showNeedInvite(msg.code === 'revoked'
              ? 'Your access was revoked by the owner.'
              : 'Access expired — open a fresh invite link.');
          } else {
            setStatus('auth failed', 'error');
            ui.connectCard.classList.remove('hidden');
            ui.view.classList.add('hidden');
            ui.connectError.textContent = 'Invalid viewer token.';
          }
        }
        break;
    }
  };

  ws.onclose = (ev) => {
    teardownCall();
    // Only show "disconnected" + reconnect for an UNEXPECTED drop. If an error
    // handler (bad_token/revoked) or the user already cleared state.ws, leave
    // the explanatory status in place instead of clobbering it.
    if (state.ws === ws) {
      setStatus(`disconnected${ev?.code ? ` (code ${ev.code}${ev.reason ? `: ${ev.reason}` : ''})` : ''}`, 'error');
      state.reconnectTimer = setTimeout(connect, 3000);
    }
  };

  ws.onerror = () => { if (state.ws === ws) setStatus('connection error', 'error'); };
}

function disconnect() {
  clearTimeout(state.reconnectTimer);
  const ws = state.ws;
  state.ws = null; // prevents auto-reconnect
  if (ws) { try { ws.send(JSON.stringify({ type: 'bye' })); } catch {} ws.close(); }
  teardownCall();
  ui.view.classList.add('hidden');
  ui.connectCard.classList.remove('hidden');
  ui.connectBtn.classList.remove('hidden'); // allow reconnect
  setStatus('disconnected');
}

// --- WebRTC ------------------------------------------------------------------

async function startCall() {
  teardownCall();
  setLive(false);
  const pc = new RTCPeerConnection({ iceServers: state.iceServers });
  state.pc = pc;

  // Receive the camera's video.
  pc.addTransceiver('video', { direction: 'recvonly' });

  // Talk-back: try to grab the mic so we can send audio. If denied, still
  // receive the camera's audio (recvonly).
  try {
    state.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const track = state.micStream.getAudioTracks()[0];
    track.enabled = false; // push-to-talk starts muted
    pc.addTrack(track, state.micStream);
    ui.micBtn.disabled = false;
  } catch {
    pc.addTransceiver('audio', { direction: 'recvonly' });
    ui.micBtn.disabled = true;
  }

  pc.ontrack = (ev) => {
    if (ui.remote.srcObject !== ev.streams[0]) {
      ui.remote.srcObject = ev.streams[0];
    }
    setLive(true);
  };

  pc.onicecandidate = (ev) => {
    if (ev.candidate) send({ type: 'ice', candidate: ev.candidate });
  };

  pc.onconnectionstatechange = () => {
    if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) setLive(false);
  };

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  send({ type: 'offer', sdp: offer.sdp });
}

function teardownCall() {
  if (state.pc) { state.pc.close(); state.pc = null; }
  if (state.micStream) { state.micStream.getTracks().forEach((t) => t.stop()); state.micStream = null; }
  state.micEnabled = false;
  ui.micBtn.setAttribute('aria-pressed', 'false');
  ui.micBtn.textContent = '🎙 Talk';
  ui.remote.srcObject = null;
}

function send(obj) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(obj));
}

// --- Talk-back / audio controls ---------------------------------------------

function toggleMic() {
  if (!state.micStream) return;
  state.micEnabled = !state.micEnabled;
  state.micStream.getAudioTracks().forEach((t) => { t.enabled = state.micEnabled; });
  ui.micBtn.setAttribute('aria-pressed', String(state.micEnabled));
  ui.micBtn.textContent = state.micEnabled ? '🎙 Talking…' : '🎙 Talk';
}

function toggleCameraAudio() {
  ui.remote.muted = !ui.remote.muted;
  ui.muteAudioBtn.setAttribute('aria-pressed', String(ui.remote.muted));
  ui.muteAudioBtn.textContent = ui.remote.muted ? '🔈 Unmute camera' : '🔇 Mute camera';
}

// --- Motion alerts -----------------------------------------------------------

function onMotion(msg) {
  const time = new Date(msg.ts || Date.now()).toLocaleTimeString();
  showToast(`Motion detected${msg.level ? ` (level ${msg.level})` : ''}`, time);
  beep();
  if ('Notification' in window && Notification.permission === 'granted') {
    new Notification('Portal Security', { body: `Motion detected at ${time}` });
  }
}

let audioCtx;
function beep() {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.001, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.2, audioCtx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.4);
    osc.connect(gain).connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.4);
  } catch {}
}

// Register the service worker so push notifications can be delivered with no
// tab open. Secure-context only (https or localhost).
async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  try {
    state.swReg = await navigator.serviceWorker.register('sw.js');
  } catch (e) {
    console.warn('SW registration failed', e);
  }
}

function pushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

function refreshNotifyButton() {
  if (!('Notification' in window)) { ui.notifyBtn.disabled = true; return; }
  if (Notification.permission === 'granted') {
    const pushable = pushSupported() && state.vapidPublicKey;
    ui.notifyBtn.textContent = pushable ? '🔔 Alerts on (background)' : '🔔 Alerts on';
  } else {
    ui.notifyBtn.textContent = '🔔 Enable alerts';
  }
}

// Enable alerts: request permission, and if the server has push configured,
// subscribe so alerts arrive even when this tab is closed.
async function enableNotifications() {
  if (!('Notification' in window)) return;
  const perm = await Notification.requestPermission();
  if (perm === 'granted' && pushSupported() && state.vapidPublicKey) {
    try {
      const reg = state.swReg || (await navigator.serviceWorker.ready);
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(state.vapidPublicKey),
      });
      send({ type: 'subscribe-push', subscription: sub.toJSON() });
    } catch (e) {
      console.warn('push subscribe failed', e);
    }
  }
  refreshNotifyButton();
}

// VAPID public keys are base64url; PushManager needs a Uint8Array.
function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

// --- Wiring ------------------------------------------------------------------

ui.connectBtn.onclick = connect;
ui.disconnectBtn.onclick = disconnect;
ui.micBtn.onclick = toggleMic;
ui.muteAudioBtn.onclick = toggleCameraAudio;
ui.notifyBtn.onclick = enableNotifications;
ui.fullscreenBtn.onclick = () => ui.remote.requestFullscreen?.();

init();
