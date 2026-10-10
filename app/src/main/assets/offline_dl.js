// runs in a hidden webview that has the real https://smolish.com page loaded. that matters: the
// site signs every /api request with its own script ("Request could not be verified" otherwise),
// so our fetch() calls go through the page's own signing. talks to SettingsActivity through
// window.SmolishOffline.
window.__smolishPrepare = async function (count) {
  var N = window.SmolishOffline;
  var VIDEO_FILE = /\.(mp4|webm|mov|m4v)(\?|#|$)/i;
  var NOT_VIDEO = /\.(jpe?g|png|webp|gif|avif|svg|m3u8)(\?|#|$)/i;

  function pick(o, paths) {
    for (var i = 0; i < paths.length; i++) {
      var v = paths[i].split('.').reduce(function (a, k) { return a == null ? a : a[k]; }, o);
      if (Array.isArray(v)) v = v.length;
      if (v != null && v !== '') return v;
    }
    return null;
  }

  // we don't know the site's field name for the video link, so look for anything that is one
  function findVideo(o, depth) {
    if (!o || typeof o !== 'object' || depth > 4) return null;
    var keys = Object.keys(o), i, v;
    for (i = 0; i < keys.length; i++) {
      v = o[keys[i]];
      if (typeof v !== 'string') continue;
      if (VIDEO_FILE.test(v)) return v;
      if (/video|playback|stream|src|file|media|url/i.test(keys[i]) && !/thumb|poster|avatar|image|banner|profile|share/i.test(keys[i]) &&
          /^(https?:)?\/\//.test(v) && !NOT_VIDEO.test(v)) return v;
    }
    for (i = 0; i < keys.length; i++) {
      v = o[keys[i]];
      if (v && typeof v === 'object') { var r = findVideo(v, depth + 1); if (r) return r; }
    }
    return null;
  }

  function sameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; } catch (e) { return false; }
  }

  // native downloads report back here
  var waiting = {};
  window.__smolishNativeDone = function (name, ok) { var w = waiting[name]; delete waiting[name]; if (w) w(ok); };
  function nativeGet(url, name) {
    return new Promise(function (res) { waiting[name] = res; N.nativeDownload(new URL(url, location.href).href, name); });
  }

  // stream a response body to the app in base64 pieces (no giant blobs or strings in memory)
  async function jsGet(url, name) {
    var r = await fetch(url, { credentials: 'include' });
    return stream(r, name);
  }
  async function stream(r, name) {
    if (!r.ok || !r.body) return false;
    if (!N.begin(name)) return false;
    var reader = r.body.getReader();
    for (;;) {
      var part = await reader.read();
      if (part.done) break;
      var bytes = part.value, s = '';
      for (var i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      if (!N.chunk(name, btoa(s))) { reader.cancel(); return false; }
    }
    return N.end(name);
  }

  // same origin goes through the webview (cloudflare), other hosts (cdn) are downloaded natively
  // since fetch would need cors there; each falls back to the other
  async function getFile(url, name) {
    if (sameOrigin(url)) return (await jsGet(url, name).catch(function () { return false; })) || nativeGet(url, name);
    return (await nativeGet(url, name)) || jsGet(url, name).catch(function () { return false; });
  }

  function ext(url, fallback) {
    var m = /\.([a-z0-9]{2,4})(\?|#|$)/i.exec(new URL(url, location.href).pathname);
    return m ? m[1].toLowerCase() : fallback;
  }

  // smolish's own "download" button (the raw file, the watermark is something the site adds in
  // the browser afterwards, and only if the watermark setting is on). answers with the video or
  // with json pointing at it
  async function viaDownload(id, file) {
    var r = await fetch('/api/videos/' + encodeURIComponent(id) + '/download', { credentials: 'include' }).catch(function () { return null; });
    if (!r || !r.ok) return null;
    var ct = (r.headers.get('content-type') || '').toLowerCase();
    if (ct.indexOf('json') >= 0) {
      var j = await r.json().catch(function () { return null; });
      var url = j && (j.url || j.downloadUrl || j.href || findVideo(j, 0));
      return url ? { url: url } : null;
    }
    if (ct.indexOf('video') >= 0 || ct.indexOf('octet-stream') >= 0) {
      var f = file + '.' + (ct.indexOf('webm') >= 0 ? 'webm' : ct.indexOf('quicktime') >= 0 ? 'mov' : 'mp4');
      return (await stream(r, f).catch(function () { return false; })) ? { file: f } : null;
    }
    return null;
  }

  async function errorText(r) {
    var t = await r.text().catch(function () { return ''; });
    try { t = JSON.parse(t).error || t; } catch (e) {}
    return r.status + (t ? ' (' + String(t).slice(0, 100) + ')' : '');
  }

  try {
    var out = [], seen = {}, cursor = null, tried = 0, sampleKeys = '', sawHls = false;
    N.progress(0, count, 'Loading your Smols…');
    while (out.length < count && tried < count * 3) {
      var r = await fetch('/api/feed' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : ''), { credentials: 'include' });
      if (!r.ok) throw Error('The feed answered ' + (await errorText(r)) + (r.status === 401 ? ', log in first' : ''));
      var page = await r.json();
      var items = page.items || [];
      if (!sampleKeys && items[0]) sampleKeys = Object.keys(items[0]).join(', ');
      for (var i = 0; i < items.length && out.length < count; i++) {
        var it = items[i];
        if (!it || !it.id || seen[it.id]) continue;
        seen[it.id] = true;
        tried++;
        var id = String(it.id).replace(/[^a-zA-Z0-9_-]/g, '_');
        N.progress(out.length, count, 'Downloading ' + (out.length + 1) + ' of ' + count);
        // 1st choice: the site's own no-watermark download
        var file = null, got = await viaDownload(it.id, id);
        if (got && got.file) file = got.file;
        var url = got && got.url ? got.url : null;
        if (!file && !url) url = findVideo(it, 0);
        if (!file && !url) {
          // not in the feed item, ask the video endpoint
          var vr = await fetch('/api/videos/' + encodeURIComponent(it.id), { credentials: 'include' }).catch(function () { return null; });
          if (vr && vr.ok) url = findVideo(await vr.json().catch(function () { return null; }), 0);
        }
        if (JSON.stringify(it).indexOf('.m3u8') >= 0) sawHls = true;
        if (!file) {
          if (!url) continue;
          file = id + '.' + ext(url, 'mp4');
          if (!(await getFile(url, file))) continue;
        }
        var avatarUrl = pick(it, ['authorAvatarUrl', 'author.avatarUrl', 'user.avatarUrl', 'avatarUrl', 'author.avatar']);
        var avatar = null;
        if (avatarUrl) {
          var af = id + '_a.' + ext(avatarUrl, 'jpg');
          if (await getFile(avatarUrl, af)) avatar = af;
        }
        out.push({
          id: it.id,
          video: file,
          avatar: avatar,
          handle: pick(it, ['authorHandle', 'author.handle', 'user.handle', 'handle', 'author.username', 'username']),
          name: pick(it, ['authorDisplayName', 'author.displayName', 'user.displayName', 'displayName']),
          title: pick(it, ['title', 'caption']),
          description: pick(it, ['description', 'body', 'text']),
          likes: pick(it, ['likeCount', 'likesCount', 'likes', 'stats.likes', 'counts.likes']),
          comments: pick(it, ['commentCount', 'commentsCount', 'comments', 'stats.comments', 'counts.comments'])
        });
        N.progress(out.length, count, 'Downloaded ' + out.length + ' of ' + count);
      }
      if (!page.nextCursor || !items.length) break;
      cursor = page.nextCursor;
    }
    if (!out.length) {
      throw Error(sawHls
        ? 'Smolish streams these videos in pieces (HLS), they can\'t be saved yet.'
        : 'Couldn\'t find the video files. Feed item fields: ' + sampleKeys);
    }
    N.finish(JSON.stringify(out));
  } catch (e) {
    N.fail(String(e && e.message || e));
  }
};
