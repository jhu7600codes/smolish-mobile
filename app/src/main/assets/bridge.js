// injected by the app (MainActivity.injectBridge). talks to the native side through window.SmolishBridge.
(function () {
  var B = window.SmolishBridge;
  if (!B || window.__smolish) return;
  window.__smolish = true;

  // --- keep screen on + picture in picture: tell the app whether any <video> is playing.
  // media events don't bubble, but a capturing listener on document still sees them.
  var playing = false;
  function checkVideos() {
    var now = Array.prototype.some.call(document.querySelectorAll('video'), function (v) {
      return !v.paused && !v.ended;
    });
    if (now !== playing) { playing = now; B.onPlayingChanged(now); }
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (e) {
    document.addEventListener(e, checkVideos, true);
  });

  // --- pull to refresh: next.js pages often scroll an inner div instead of the window,
  // so WebView.scrollY alone isn't enough. track the scroll position of whatever scrolled last.
  var atTop = true;
  document.addEventListener('scroll', function (e) {
    var t = e.target;
    var top = (t === document || t === document.documentElement || t === document.body)
      ? window.scrollY : t.scrollTop;
    var now = top <= 0;
    if (now !== atTop) { atTop = now; B.onScrollTop(now); }
  }, { capture: true, passive: true });

  // --- system bar colors: read the background color of the site's bottom nav (right above the
  // gesture bar), the app paints both the status bar and the gesture bar with it.
  // only runs when something changed (load, navigation, scroll stop, resize), never on a timer,
  // so it costs nothing while swiping through videos.
  var cv, cx;
  function hex(r, g, b) {
    return '#' + [r, g, b].map(function (v) { v = Math.round(v); return (v < 16 ? '0' : '') + v.toString(16); }).join('');
  }
  function toHex(css) {
    // fast path, computed colors are almost always rgb()/rgba()
    var m = /^rgba?\(([\d.]+)[, ]+([\d.]+)[, ]+([\d.]+)(?:[,\/ ]+([\d.]+))?\)$/.exec(css);
    if (m) return m[4] !== undefined && +m[4] < 0.5 ? '' : hex(+m[1], +m[2], +m[3]);
    // anything else (oklch, color(...)): let a 1x1 canvas convert it
    if (!cv) {
      cv = document.createElement('canvas');
      cv.width = cv.height = 1;
      cx = cv.getContext('2d', { willReadFrequently: true });
    }
    cx.clearRect(0, 0, 1, 1);
    cx.fillStyle = 'rgba(0,0,0,0)';
    cx.fillStyle = css;
    cx.fillRect(0, 0, 1, 1);
    var d = cx.getImageData(0, 0, 1, 1).data;
    if (d[3] < 128) return ''; // (mostly) transparent, look at the parent instead
    return hex(d[0], d[1], d[2]);
  }
  function colorAt(y) {
    var el = document.elementFromPoint(window.innerWidth / 2, y);
    for (; el; el = el.parentElement) {
      var c = toHex(getComputedStyle(el).backgroundColor);
      if (c) return c;
    }
    return '';
  }
  var lastBar = null;
  function checkBars() {
    var c = colorAt(window.innerHeight - 1);
    if (c !== lastBar) { lastBar = c; B.onBarColor(c); }
  }
  var barTimer = 0;
  function scheduleBars(delay) {
    clearTimeout(barTimer);
    barTimer = setTimeout(checkBars, delay);
  }
  checkBars();
  scheduleBars(1500); // again once late styles / hydration settled
  window.addEventListener('resize', function () { scheduleBars(300); });
  document.addEventListener('scroll', function () { scheduleBars(400); }, { capture: true, passive: true });

  // the app calls this on client side navigation: new page starts at the top, maybe new colors
  window.__smolishOnNav = function () {
    atTop = true;
    scheduleBars(600);
  };

  // --- sound bug workaround: after a few videos the webview's audio starts cutting out and
  // glitching, a fresh page load fixes it. count the videos that start playing; when the 4th one
  // starts (3 watched) the app reloads behind the cube loader, and we stop that video right away
  // so it doesn't play sound or draw frames before the screen gets frozen.
  // a video is identified by element + how many times it loaded a source, not by its url:
  // streamed videos (MediaSource via srcObject) have no url at all, and feeds often recycle a
  // few <video> elements for every clip
  var started = 0, lastVideo = null, ids = 0;
  document.addEventListener('loadstart', function (e) {
    var v = e.target;
    if (v instanceof HTMLVideoElement) v.__smolishLoads = (v.__smolishLoads || 0) + 1;
  }, true);
  document.addEventListener('play', function (e) {
    var v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    if (!v.__smolishId) v.__smolishId = ++ids;
    var key = v.__smolishId + ':' + (v.__smolishLoads || 0) + ':' + (v.currentSrc || v.src || '');
    if (key === lastVideo) return; // same clip resumed or looping
    lastVideo = key;
    if (++started > 3 && B.refreshForSound()) {
      v.muted = true;
      v.pause();
    }
  }, true);

  // --- after a sound reload: hide the Smols/Friends top bar (the site doesn't show it this deep
  // into the feed). it's found by its text, then we hide the whole bar around it (the widest
  // short box), and keep doing that for a few seconds since the site may render it late.
  window.__smolishHideTopbar = function () {
    function find() {
      var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) { return n.nodeValue.trim() === 'Smols' ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP; }
      });
      for (var t = walk.nextNode(); t; t = walk.nextNode()) {
        var el = t.parentElement;
        while (el && el.textContent.indexOf('Friends') < 0) el = el.parentElement;
        if (!el) continue;
        // climb to the bar itself: the biggest ancestor that's still short (not the whole page)
        while (el.parentElement && el.parentElement !== document.body &&
               el.parentElement.getBoundingClientRect().height < 160) el = el.parentElement;
        return el;
      }
      return null;
    }
    function hide() {
      var bar = find();
      if (bar && bar.style.visibility !== 'hidden') bar.style.visibility = 'hidden';
    }
    hide();
    var queued = false;
    var mo = new MutationObserver(function () {
      if (queued) return;
      queued = true;
      requestAnimationFrame(function () { queued = false; hide(); });
    });
    mo.observe(document.body, { childList: true, subtree: true });
    setTimeout(function () { mo.disconnect(); }, 8000);
  };

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
  setTimeout(checkNotifs, 3000);
  setInterval(checkNotifs, 60000);

  // --- blob downloads.
  // a blob: url only exists inside this page, so DownloadManager can't fetch it.
  // instead we read the blob here, turn it into a base64 data url and hand that to native code,
  // which decodes it and writes the file. the filename comes from <a download="...">, which we
  // remember when the site clicks it (also when it clicks a detached anchor from code).
  var lastName = '';
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[download]');
    if (a) lastName = a.getAttribute('download') || '';
  }, true);
  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download')) lastName = this.getAttribute('download') || '';
    return anchorClick.apply(this, arguments);
  };

  window.__smolishBlob = function (url, mime) {
    fetch(url)
      .then(function (r) { return r.blob(); })
      .then(function (blob) {
        var reader = new FileReader();
        reader.onloadend = function () {
          B.saveBase64(reader.result, mime || blob.type || '', lastName);
          lastName = '';
        };
        reader.readAsDataURL(blob);
      })
      .catch(function () { B.saveBase64('', '', ''); });
  };
})();
