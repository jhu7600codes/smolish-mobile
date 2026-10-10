// runs before smolish.com's own scripts. android's webview has no web push and no Notification
// api, so the site says "not supported". this gives it both: subscribing creates a real push
// subscription (an ntfy.sh topic with keys made on the phone, see WebPush.kt), and the app shows
// whatever smolish's server pushes there as android notifications.
(function () {
  var B = window.SmolishBridge;
  if (!B || window.__smolishPush) return;
  window.__smolishPush = true;

  function toBuf(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s), a = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a.buffer;
  }

  function PushSubscription() {}
  function makeSub(j, key) {
    var sub = Object.create(PushSubscription.prototype);
    sub.endpoint = j.endpoint;
    sub.expirationTime = null;
    sub.options = { userVisibleOnly: true, applicationServerKey: key || null };
    sub.getKey = function (n) { return n === 'p256dh' ? toBuf(j.p256dh) : n === 'auth' ? toBuf(j.auth) : null; };
    sub.toJSON = function () { return { endpoint: j.endpoint, expirationTime: null, keys: { p256dh: j.p256dh, auth: j.auth } }; };
    sub.unsubscribe = function () { B.pushUnsubscribe(); return Promise.resolve(true); };
    return sub;
  }
  window.PushSubscription = PushSubscription;

  // notification permission = android's notification permission
  function perm() { return B.notifPermission(); }
  var waiting = [];
  window.__smolishPermResult = function (p) {
    var w = waiting; waiting = [];
    w.forEach(function (f) { f(p); });
  };
  function askPerm() {
    var p = perm();
    if (p !== 'default') return Promise.resolve(p);
    return new Promise(function (res) { waiting.push(res); B.requestNotifPermission(); });
  }

  function PushManager() {}
  PushManager.supportedContentEncodings = ['aes128gcm'];
  PushManager.prototype.subscribe = function (opts) {
    var key = opts && opts.applicationServerKey;
    return askPerm().then(function (p) {
      if (p !== 'granted') throw new DOMException('Notifications are not allowed', 'NotAllowedError');
      var r = B.pushSubscribe();
      if (!r) throw new DOMException('Push service not available', 'AbortError');
      return makeSub(JSON.parse(r), key);
    });
  };
  PushManager.prototype.getSubscription = function () {
    var r = B.pushSubscription();
    return Promise.resolve(r ? makeSub(JSON.parse(r)) : null);
  };
  PushManager.prototype.permissionState = function () {
    var p = perm();
    return Promise.resolve(p === 'default' ? 'prompt' : p);
  };
  window.PushManager = PushManager;
  var manager = new PushManager();
  if (window.ServiceWorkerRegistration) {
    Object.defineProperty(ServiceWorkerRegistration.prototype, 'pushManager', { get: function () { return manager; }, configurable: true });
  }

  function Notification(title, o) {
    o = o || {};
    B.showLocal(String(title || 'Smolish'), String(o.body || ''), String((o.data && o.data.url) || ''));
    this.title = title; this.body = o.body || ''; this.data = o.data;
    this.close = function () {};
    this.addEventListener = function () {};
  }
  Object.defineProperty(Notification, 'permission', { get: perm, configurable: true });
  Notification.requestPermission = function (cb) {
    return askPerm().then(function (p) { if (cb) cb(p); return p; });
  };
  Notification.maxActions = 0;
  window.Notification = Notification;
  if (window.ServiceWorkerRegistration && !ServiceWorkerRegistration.prototype.showNotification) {
    ServiceWorkerRegistration.prototype.showNotification = function (t, o) { new Notification(t, o); return Promise.resolve(); };
    ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
  }

  // some sites ask the permissions api instead
  if (navigator.permissions && navigator.permissions.query) {
    var query = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function (d) {
      if (d && (d.name === 'notifications' || d.name === 'push')) {
        var p = perm();
        return Promise.resolve({ name: d.name, state: p === 'default' ? 'prompt' : p, onchange: null,
          addEventListener: function () {}, removeEventListener: function () {} });
      }
      return query(d);
    };
  }
})();
