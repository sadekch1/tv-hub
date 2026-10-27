'use strict';
// خادم محلي (127.0.0.1 فقط) يقدّم الواجهة والأجزاء ويربط الواجهة بالـ Hub.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const m3u = require('./m3u');

function createServer(hub, opts) {
  const { dataDir, uiDir } = opts;
  const token = opts.token || crypto.randomBytes(12).toString('hex');
  const chFile = path.join(dataDir, 'channels.json');
  const logoDir = path.join(dataDir, 'logos');
  fs.mkdirSync(logoDir, { recursive: true });

  let channels = [];
  try { channels = JSON.parse(fs.readFileSync(chFile, 'utf8')); } catch (_) {}

  const state = () => ({ ...hub.getState(), channels: channels.length });
  const clients = new Set();
  const push = (ev, data) => {
    const s = `event: ${ev}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const r of clients) r.write(s);
  };
  hub.on('status', () => push('state', state()));
  hub.on('part', (p) => push('part', p));
  hub.on('bot', (t) => push('bot', t));
  setInterval(() => { for (const r of clients) r.write(': ping\n\n'); }, 20000).unref();

  const readBody = (req) => new Promise((ok, no) => {
    let n = 0; const chunks = [];
    req.on('data', (c) => { n += c.length; if (n > 40e6) { no(new Error('الطلب كبير جداً')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { ok(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); } catch (_) { no(new Error('JSON غير صالح')); } });
  });

  function serveFile(req, res, file, type) {
    let st;
    try { st = fs.statSync(file); } catch (_) { res.writeHead(404); return res.end(); }
    const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
    if (!m) {
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Accept-Ranges': 'bytes' });
      return fs.createReadStream(file).pipe(res);
    }
    let s = m[1] ? +m[1] : 0;
    let e = m[2] ? +m[2] : st.size - 1;
    if (!m[1] && m[2]) { s = Math.max(0, st.size - +m[2]); e = st.size - 1; }
    e = Math.min(e, st.size - 1);
    if (s > e) { res.writeHead(416, { 'Content-Range': `bytes */${st.size}` }); return res.end(); }
    res.writeHead(206, { 'Content-Type': type, 'Content-Length': e - s + 1, 'Content-Range': `bytes ${s}-${e}/${st.size}`, 'Accept-Ranges': 'bytes' });
    fs.createReadStream(file, { start: s, end: e }).pipe(res);
  }

  const srv = http.createServer(async (req, res) => {
    const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
    try {
      const u = new URL(req.url, 'http://x');
      const port = srv.address().port;
      // حماية من DNS rebinding ومن أي تطبيق آخر على الجهاز: يلزم المضيف الصحيح والرمز
      if (req.headers.host !== `127.0.0.1:${port}` || u.searchParams.get('t') !== token) return send(403, { error: 'forbidden' });
      const p = u.pathname;

      if (p === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(fs.readFileSync(path.join(uiDir, 'index.html')));
      }
      if (p === '/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        res.write('retry: 2000\n\n');
        clients.add(res);
        req.on('close', () => clients.delete(res));
        return res.write(`event: state\ndata: ${JSON.stringify(state())}\n\n`);
      }
      let m;
      if ((m = /^\/part\/(\d+)$/.exec(p))) {
        const n = +m[1];
        if (!hub.parts.has(n)) { res.writeHead(404); return res.end(); }
        return serveFile(req, res, hub.partPath(n), 'video/mp4');
      }
      if ((m = /^\/logo\/([a-f0-9]{12})$/.exec(p))) return serveFile(req, res, path.join(logoDir, m[1]), 'image/png');

      if (p === '/api/state') return send(200, state());
      if (p === '/api/channels') return send(200, channels.map((c) => ({ id: c.id, name: c.name, group: c.group, logoId: c.logoId })));

      if (req.method === 'POST') {
        const j = await readBody(req);
        if (p === '/api/pair') return send(200, { code: await hub.pair(j.phone) });
        if (p === '/api/settings') return send(200, hub.saveSettings(j));
        if (p === '/api/play') {
          const ch = channels[+j.id];
          if (!ch) throw new Error('القناة غير موجودة');
          await hub.play(ch);
          return send(200, { ok: true });
        }
        if (p === '/api/stop') { await hub.stop(); return send(200, { ok: true }); }
        if (p === '/api/played') { hub.prune(+j.n || 0); return send(200, { ok: true }); }
        if (p === '/api/import') {
          const text = j.text || await m3u.fetchText(j.url);
          const list = m3u.parse(text);
          if (!list.length) throw new Error('لم أجد قنوات في القائمة. تأكد أنها بصيغة m3u.');
          channels = list;
          fs.writeFileSync(chFile, JSON.stringify(channels));
          send(200, { count: list.length });
          m3u.cacheLogos(channels, logoDir).then(() => push('logos', {})).catch(() => {});
          return;
        }
      }
      send(404, { error: 'not found' });
    } catch (e) {
      send(400, { error: e.message });
    }
  });

  return {
    start() {
      return new Promise((resolve) => {
        srv.listen(opts.port || 0, '127.0.0.1', () => {
          const port = srv.address().port;
          resolve({ port, token, url: `http://127.0.0.1:${port}/?t=${token}` });
        });
      });
    },
    close() { srv.close(); },
  };
}

module.exports = { createServer };
