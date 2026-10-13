// Notifications only: no cached conversations, credentials or offline app storage.
self.addEventListener('push', event => {
  let notice;
  try { notice = event.data.json(); } catch (_) { return; }
  const destination = /^(tasks|activity|code|brief|chat:[A-Za-z0-9_-]{1,100})$/.test(notice.open) ? notice.open : 'activity';
  event.waitUntil(self.clients.matchAll({type:'window', includeUncontrolled:true}).then(windows => {
    if (windows.some(client => client.visibilityState === 'visible' && client.focused)) return;
    return self.registration.showNotification(String(notice.title || 'FRIDAY').slice(0,100), {
      body:String(notice.body || 'Open FRIDAY to review it.').slice(0,200),
      tag:String(notice.tag || 'friday').slice(0,200), data:{destination}
    });
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const destination = event.notification.data?.destination || 'activity';
  event.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(async windows => {
    const target = windows.find(client => client.url.startsWith(self.registration.scope));
    if (target) { await target.focus(); target.postMessage({kind:'friday-notice',open:destination}); }
    else await self.clients.openWindow(self.registration.scope + '#notice=' + encodeURIComponent(destination));
  }));
});
