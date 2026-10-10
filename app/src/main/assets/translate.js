// injected by the app when "Translate Smolish" is on. swaps the page's english text for
// translations from the app (SmolishBridge.translate -> window.__smolishTrDone), including
// everything the site adds later. long-press any text to flip between original and translation.
(function () {
  var B = window.SmolishBridge;
  if (!B || window.__smolishTr) return;
  window.__smolishTr = true;

  var rec = new WeakMap();   // text node -> { src, out, hold }
  var waiting = new Map();   // source text -> [text nodes]
  var queue = [];
  var reqs = {};
  var seq = 0;
  var timer = 0;

  function skip(el) {
    for (; el; el = el.parentElement) {
      var t = el.tagName;
      if (t === 'SCRIPT' || t === 'STYLE' || t === 'NOSCRIPT' || t === 'TEXTAREA' || t === 'CODE' || t === 'PRE' ||
          t === 'SVG' || t === 'svg' || el.isContentEditable || el.getAttribute('translate') === 'no') return true;
    }
    return false;
  }

  // only real words: no handles, links, numbers or emoji-only text
  function worth(s) {
    var t = s.trim();
    return /[A-Za-z]{2,}/.test(t) && !/^@[\w.]+$/.test(t) && !/^(https?:\/\/|www\.)\S+$/.test(t) && t.length < 3000;
  }

  function handle(node) {
    var s = node.nodeValue;
    var r = rec.get(node);
    if (r && (s === r.out || (r.hold && s === r.src))) return; // our own change
    if (!s || !worth(s) || skip(node.parentElement)) return;
    var key = s.trim();
    var list = waiting.get(key);
    if (list) { list.push(node); return; }
    waiting.set(key, [node]);
    queue.push(key);
    if (!timer) timer = setTimeout(flush, 120);
  }

  function flush() {
    timer = 0;
    if (!queue.length) return;
    var id = ++seq;
    reqs[id] = queue;
    queue = [];
    B.translate(id, JSON.stringify(reqs[id]));
  }

  window.__smolishTrDone = function (id, out) {
    var keys = reqs[id];
    delete reqs[id];
    if (!keys) return;
    keys.forEach(function (key, i) {
      var nodes = waiting.get(key) || [];
      waiting.delete(key);
      var tr = out && out[i];
      if (!tr) return;
      nodes.forEach(function (n) {
        var s = n.nodeValue;
        if (!s || s.trim() !== key) return; // the site changed it meanwhile
        var v = s.match(/^\s*/)[0] + tr + s.match(/\s*$/)[0];
        rec.set(n, { src: s, out: v, hold: false });
        n.nodeValue = v;
      });
    });
  };

  function walk(root) {
    if (root.nodeType === 3) return handle(root);
    if (root.nodeType !== 1 || skip(root)) return;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (var n = w.nextNode(); n; n = w.nextNode()) handle(n);
    placeholders(root);
  }

  // input hints ("Add a comment...") are attributes, not text
  function placeholders(root) {
    if (!root.querySelectorAll) return;
    var els = root.matches && root.matches('[placeholder]') ? [root] : [];
    root.querySelectorAll('[placeholder]').forEach(function (e) { els.push(e); });
    els.forEach(function (e) {
      var p = e.getAttribute('placeholder');
      if (!p || e.__smolishPh === p || !worth(p)) return;
      var id = ++seq;
      reqs[id] = [];
      window['__smolishPh' + id] = e;
      B.translate(-id, JSON.stringify([p.trim()]));
    });
  }
  var doneText = window.__smolishTrDone;
  window.__smolishTrDone = function (id, out) {
    if (id > 0) return doneText(id, out);
    var e = window['__smolishPh' + -id];
    delete window['__smolishPh' + -id];
    delete reqs[-id];
    if (e && out && out[0]) { e.__smolishPh = out[0]; e.setAttribute('placeholder', out[0]); }
  };

  new MutationObserver(function (list) {
    list.forEach(function (m) {
      if (m.type === 'characterData') handle(m.target);
      else m.addedNodes.forEach(walk);
    });
  }).observe(document.body, { childList: true, subtree: true, characterData: true });

  // give react a moment to finish hydrating before touching its text
  setTimeout(function () { walk(document.body); }, 500);

  // --- long-press: show the original
  var press = 0, sx = 0, sy = 0;
  document.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    sx = t.clientX; sy = t.clientY;
    var el = e.target;
    clearTimeout(press);
    press = setTimeout(function () { flip(el); }, 550);
  }, { passive: true, capture: true });
  document.addEventListener('touchmove', function (e) {
    var t = e.touches[0];
    if (Math.abs(t.clientX - sx) + Math.abs(t.clientY - sy) > 12) clearTimeout(press);
  }, { passive: true, capture: true });
  ['touchend', 'touchcancel'].forEach(function (n) {
    document.addEventListener(n, function () { clearTimeout(press); }, { passive: true, capture: true });
  });

  function flip(el) {
    if (!el || el.tagName === 'VIDEO') return;
    var nodes = [];
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (var n = w.nextNode(); n && nodes.length < 40; n = w.nextNode()) if (rec.get(n)) nodes.push(n);
    if (!nodes.length) return;
    var toSrc = !rec.get(nodes[0]).hold;
    nodes.forEach(function (n) {
      var r = rec.get(n);
      r.hold = toSrc;
      n.nodeValue = toSrc ? r.src : r.out;
    });
    var sel = window.getSelection && window.getSelection();
    setTimeout(function () { sel && sel.removeAllRanges(); }, 50);
  }
})();
