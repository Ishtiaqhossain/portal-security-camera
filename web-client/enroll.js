// Completes device enrollment after the user scans the QR shown on the Portal.
// The enrollment token rides in the URL FRAGMENT (#t=...), which browsers never
// send to the server or log — it's read here client-side and POSTed over HTTPS.
const msg = document.getElementById('msg');
const cont = document.getElementById('continue');

// Read the token from the fragment (#t=...), falling back to ?t= just in case.
function getToken() {
  const hash = new URLSearchParams(location.hash.replace(/^#/, ''));
  return hash.get('t') || new URLSearchParams(location.search).get('t');
}

async function enroll() {
  const token = getToken();
  if (!token) { fail('No enrollment code in this link. Generate a fresh QR on the camera.'); return; }
  let res;
  try {
    res = await fetch('/auth/enroll', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token }),
    });
  } catch {
    fail('Could not reach the camera server. Make sure you are on the same Wi-Fi and try again.');
    return;
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    fail(err.error || 'This enrollment code is invalid or expired.');
    return;
  }
  const data = await res.json();
  localStorage.setItem('portal-refresh', data.refreshToken);
  localStorage.setItem('portal-viewer-name', data.viewer.name);
  msg.textContent = `Enrolled as "${data.viewer.name}". Opening the camera…`;
  msg.className = 'status connected';
  cont.classList.remove('hidden');
  // Clear the token from the URL before moving on.
  history.replaceState(null, '', '/enroll.html');
  setTimeout(() => { location.href = '/'; }, 1400);
}

function fail(text) {
  msg.textContent = text;
  msg.className = 'status error';
}

enroll();
