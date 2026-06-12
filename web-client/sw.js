// Service worker for the Portal Security viewer.
// Its job is to receive Web Push messages and show motion notifications even
// when no viewer tab is open, and to focus/open the viewer when tapped.

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { /* non-JSON */ }
  const title = data.title || 'Portal Security';
  const time = data.ts ? new Date(data.ts).toLocaleTimeString() : '';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: (data.body || 'Motion detected') + (time ? ` at ${time}` : ''),
      tag: 'portal-motion',     // collapse repeats into one
      renotify: true,           // but still buzz on a new one
      requireInteraction: false,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      data: { url: '/' },
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if ('focus' in client) return client.focus(); // reuse an open tab
      }
      return self.clients.openWindow(url);
    })
  );
});
