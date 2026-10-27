'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const logoId = (u) => crypto.createHash('sha1').update(u).digest('hex').slice(0, 12);

// تحليل ملف m3u مع الاحتفاظ بالأيقونة والمجموعة
function parse(text) {
  const out = [];
  const seen = new Set();
  let meta = null;
  for (const raw of String(text).split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTINF')) {
      const attr = (k) => (new RegExp(k + '="([^"]*)"', 'i').exec(line) || [])[1] || '';
      // أول فاصلة خارج علامات الاقتباس تفصل الخصائص عن الاسم
      const masked = line.replace(/"[^"]*"/g, (m) => '_'.repeat(m.length));
      const i = masked.indexOf(',');
      const name = (i >= 0 ? line.slice(i + 1).trim() : '') || attr('tvg-name') || 'بدون اسم';
      meta = { name, group: attr('group-title'), logo: attr('tvg-logo') };
    } else if (line[0] !== '#' && /^https?:\/\/\S+$/i.test(line) && meta) {
      if (!seen.has(line)) {
        seen.add(line);
        const logo = /^https?:\/\//i.test(meta.logo) ? meta.logo : '';
        out.push({ id: out.length, name: meta.name, group: meta.group, url: line, logo, logoId: logo ? logoId(logo) : '' });
      }
      meta = null;
    }
  }
  return out;
}

async function fetchText(url) {
  if (!/^https?:\/\//i.test(url || '')) throw new Error('الرابط يجب أن يبدأ بـ http:// أو https://');
  const r = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(60000) });
  if (!r.ok) throw new Error('فشل جلب القائمة (HTTP ' + r.status + ')');
  return await r.text();
}

// تنزيل الأيقونات مرة واحدة وحفظها في الجهاز (8 بالتوازي)
async function cacheLogos(channels, dir) {
  fs.mkdirSync(dir, { recursive: true });
  const todo = [...new Map(channels.filter((c) => c.logo).map((c) => [c.logoId, c.logo])).entries()]
    .filter(([id]) => !fs.existsSync(path.join(dir, id)));
  let i = 0;
  const worker = async () => {
    while (i < todo.length) {
      const [id, url] = todo[i++];
      try {
        const r = await fetch(url, { signal: AbortSignal.timeout(15000) });
        if (!r.ok) continue;
        const buf = Buffer.from(await r.arrayBuffer());
        if (buf.length > 0 && buf.length < 600 * 1024) fs.writeFileSync(path.join(dir, id), buf);
      } catch (_) { /* نتجاهل أيقونة فاشلة */ }
    }
  };
  await Promise.all(Array.from({ length: 8 }, worker));
}

module.exports = { parse, fetchText, cacheLogos };
