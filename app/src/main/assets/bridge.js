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
  // the app calls this on client side navigation, new pages start at the top
  window.__smolishResetScroll = function () { atTop = true; };

  // --- system bar colors: read the background color of the site's bottom nav (right above the
  // gesture bar), the app paints both the status bar and the gesture bar with it.
  // a 1x1 canvas turns any css color (rgb, oklch, ...) into plain rgba numbers.
  var cv = document.createElement('canvas');
  cv.width = cv.height = 1;
  var cx = cv.getContext('2d', { willReadFrequently: true });
  function toHex(css) {
    cx.clearRect(0, 0, 1, 1);
    cx.fillStyle = 'rgba(0,0,0,0)';
    cx.fillStyle = css;
    cx.fillRect(0, 0, 1, 1);
    var d = cx.getImageData(0, 0, 1, 1).data;
    if (d[3] < 128) return ''; // (mostly) transparent, look at the parent instead
    return '#' + [d[0], d[1], d[2]].map(function (v) { return (v < 16 ? '0' : '') + v.toString(16); }).join('');
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
  checkBars();
  setInterval(checkBars, 700); // cheap, and catches page changes, modals and theme switches

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
