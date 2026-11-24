/* Chore Slayer service worker: shows push notifications and opens the app
   when one is tapped. It does no offline caching. */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
    let data = {};
    try { data = event.data ? event.data.json() : {}; } catch (e) { data = { body: event.data && event.data.text() }; }
    const title = data.title || 'Chore Slayer';
    event.waitUntil(self.registration.showNotification(title, {
        body: data.body || '',
        icon: 'assets/icon-192.png',
        badge: 'assets/icon-192.png',
        tag: data.tag || undefined,
        data: { url: './' },
    }));
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();
    const url = new URL((event.notification.data && event.notification.data.url) || './', self.registration.scope).href;
    event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
        for (const c of list) {
            if (c.url.startsWith(self.registration.scope) && 'focus' in c) return c.focus();
        }
        return self.clients.openWindow(url);
    }));
});
