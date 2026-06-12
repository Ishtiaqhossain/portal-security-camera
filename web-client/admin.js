// Portal Security admin console: owner sign-in, viewer management (invite +
// revoke), and the activity log. The admin token lives in sessionStorage so a
// refresh keeps you signed in but closing the tab signs you out.

const $ = (id) => document.getElementById(id);
let adminToken = sessionStorage.getItem('portal-admin') || null;

const api = (path, method = 'GET', body) =>
  fetch(path, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(adminToken ? { authorization: `Bearer ${adminToken}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

// --- Auth -------------------------------------------------------------------

async function login() {
  $('login-error').textContent = '';
  const res = await api('/auth/admin', 'POST', { password: $('password').value });
  if (!res.ok) { $('login-error').textContent = 'Incorrect password.'; return; }
  adminToken = (await res.json()).adminToken;
  sessionStorage.setItem('portal-admin', adminToken);
  showDashboard();
}

function logout() {
  adminToken = null;
  sessionStorage.removeItem('portal-admin');
  $('dashboard').classList.add('hidden');
  $('login-card').classList.remove('hidden');
  $('logout').classList.add('hidden');
}

async function showDashboard() {
  $('login-card').classList.add('hidden');
  $('dashboard').classList.remove('hidden');
  $('logout').classList.remove('hidden');
  await Promise.all([loadViewers(), loadCameras(), loadAudit()]);
}

// --- Viewers ----------------------------------------------------------------

async function loadViewers() {
  const res = await api('/admin/viewers');
  if (res.status === 401) { logout(); return; }
  const { viewers } = await res.json();
  const body = $('viewers-body');
  body.innerHTML = '';
  $('viewers-empty').style.display = viewers.length ? 'none' : 'block';
  for (const v of viewers) {
    const tr = document.createElement('tr');
    const seen = v.lastSeenAt ? new Date(v.lastSeenAt).toLocaleString() : '—';
    tr.innerHTML = `
      <td>${escapeHtml(v.name)}</td>
      <td><span class="pill ${v.revoked ? 'revoked' : 'active'}">${v.revoked ? 'Revoked' : 'Active'}</span></td>
      <td>${seen}</td>
      <td class="row-actions"></td>`;
    const btn = document.createElement('button');
    if (v.revoked) {
      btn.textContent = 'Re-enable';
      btn.onclick = () => setRevoked(v.id, false);
    } else {
      btn.textContent = 'Revoke';
      btn.className = 'danger';
      btn.onclick = () => setRevoked(v.id, true);
    }
    tr.querySelector('.row-actions').appendChild(btn);
    body.appendChild(tr);
  }
}

async function setRevoked(id, revoked) {
  await api(revoked ? '/admin/revoke' : '/admin/enable', 'POST', { id });
  await Promise.all([loadViewers(), loadAudit()]);
}

// --- Cameras ----------------------------------------------------------------

async function loadCameras() {
  const res = await api('/admin/cameras');
  if (res.status === 401) { logout(); return; }
  const { cameras } = await res.json();
  const body = $('cameras-body');
  body.innerHTML = '';
  $('cameras-empty').style.display = cameras.length ? 'none' : 'block';
  for (const c of cameras) {
    const tr = document.createElement('tr');
    const seen = c.lastSeenAt ? new Date(c.lastSeenAt).toLocaleString() : '—';
    tr.innerHTML = `
      <td>${escapeHtml(c.name)}</td>
      <td><span class="pill ${c.revoked ? 'revoked' : 'active'}">${c.revoked ? 'Revoked' : 'Active'}</span></td>
      <td>${seen}</td>
      <td class="row-actions"></td>`;
    const btn = document.createElement('button');
    if (c.revoked) {
      btn.textContent = 'Re-enable';
      btn.onclick = () => setCameraRevoked(c.id, false);
    } else {
      btn.textContent = 'Revoke';
      btn.className = 'danger';
      btn.onclick = () => setCameraRevoked(c.id, true);
    }
    tr.querySelector('.row-actions').appendChild(btn);
    body.appendChild(tr);
  }
}

async function setCameraRevoked(id, revoked) {
  // Revoking disconnects the live camera, so confirm first; re-enabling is safe.
  if (revoked && !confirm('Revoke this camera? It will be disconnected, and a reset or replacement Portal can then claim the system on its next Arm.')) return;
  await api(revoked ? '/admin/camera-revoke' : '/admin/camera-enable', 'POST', { id });
  await Promise.all([loadCameras(), loadAudit()]);
}

// --- Invites ----------------------------------------------------------------

async function createInvite() {
  const name = $('invite-name').value.trim() || 'Viewer';
  const res = await api('/admin/invite', 'POST', { name });
  if (res.status === 401) { logout(); return; }
  const { code } = await res.json();
  const link = `${location.origin}/redeem.html?code=${encodeURIComponent(code)}`;
  $('invite-link').textContent = link;
  $('invite-result').classList.remove('hidden');
  $('copy-invite').onclick = async () => {
    try { await navigator.clipboard.writeText(link); $('copy-invite').textContent = 'Copied ✓'; }
    catch { /* clipboard may be blocked; user can select manually */ }
  };
  $('invite-name').value = '';
  await loadViewers();
}

// --- Audit ------------------------------------------------------------------

async function loadAudit() {
  const res = await api('/admin/audit');
  if (res.status === 401) { logout(); return; }
  const { audit } = await res.json();
  $('audit').innerHTML = audit.map((e) => {
    const t = new Date(e.ts).toLocaleString();
    const who = e.name ? ` ${escapeHtml(e.name)}` : '';
    const ip = e.ip ? ` (${escapeHtml(e.ip)})` : '';
    return `<div>${t} · <b>${escapeHtml(e.event)}</b>${who}${ip}</div>`;
  }).join('') || '<div>No activity yet.</div>';
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// --- Wiring -----------------------------------------------------------------

$('login-btn').onclick = login;
$('password').addEventListener('keydown', (e) => { if (e.key === 'Enter') login(); });
$('create-invite').onclick = createInvite;
$('logout').onclick = logout;

// Resume an existing admin session if the token is still valid.
if (adminToken) {
  api('/admin/viewers').then((res) => { if (res.ok) showDashboard(); else logout(); });
}
