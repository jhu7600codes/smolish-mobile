// new-notification polling. injected into the app's page while the background service is off,
// and into the background service's hidden page (window.SmolishNotify) when it's on.
(function () {
  var B = window.SmolishNotify || window.SmolishBridge;
  if (!B || window.__smolishNotifPoll) return;
  window.__smolishNotifPoll = true;

  // --- notifications: android webviews can't do web push, so ask the site's own unread counter
  // (the same request the site makes every minute, signed by the site's own fetch). when it goes up,
  // grab the newest notification's text and let the app show a real android notification.
  // the last count the app saw comes from window.__smolishLastUnread, so reloads don't re-notify.
  function pickPath(o, paths) {
    for (var i = 0; i < paths.length; i++) {
      var v = paths[i].split('.').reduce(function (a, k) { return a == null ? a : a[k]; }, o);
      if (v != null && v !== '') return String(v);
    }
    return '';
  }
  var VERBS = { like: 'liked your smol', comment: 'commented on your smol', reply: 'replied to you', follow: 'followed you',
    friend: 'sent you a friend request', mention: 'mentioned you', message: 'sent you a message' };
  async function checkNotifs() {
    try {
      var r = await fetch('/api/notifications/unread');
      if (!r.ok) return;
      var j = await r.json().catch(function () { return null; });
      var n = (j && j.unread) || 0;
      var last = typeof window.__smolishLastUnread === 'number' ? window.__smolishLastUnread : -1;
      window.__smolishLastUnread = n;
      B.onUnread(n);
      if (last < 0 || n <= last) return;
      var who = '', what = '';
      try {
        var lr = await fetch('/api/notifications?page=1');
        var lj = lr.ok ? await lr.json() : null;
        var list = lj && (lj.items || lj.notifications || lj.data || (Array.isArray(lj) ? lj : null));
        var it = list && list[0];
        if (it) {
          who = pickPath(it, ['actor.displayName', 'actor.handle', 'actorName', 'fromUser.displayName', 'user.displayName', 'sender.displayName', 'author.displayName', 'actorHandle']);
          what = pickPath(it, ['message', 'text', 'body', 'summary', 'title']);
          if (!what) what = VERBS[String(it.type || it.kind || '').toLowerCase().replace(/[^a-z]/g, '')] || '';
        }
      } catch (e) {}
      B.onNotification(n, who, what);
    } catch (e) {}
  }
  window.__smolishCheckNotifs = checkNotifs;
  // in the app the page polls itself; the background service calls __smolishCheckNotifs from native
  if (!window.__smolishNativePoll) {
    setTimeout(checkNotifs, 3000);
    setInterval(checkNotifs, 60000);
  }
})();
