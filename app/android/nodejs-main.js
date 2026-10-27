'use strict';
// نقطة دخول nodejs-mobile (Android). ضعها كـ main.js داخل nodejs-project
// بجانب مجلدي core/ و ui/. تُشغّل نفس الـ Hub ونفس الخادم المحلي،
// وتُعلم تطبيق الأندرويد بالرابط ليفتحه داخل WebView.
const fs = require('fs');
const path = require('path');
let bridge = null;
for (const n of ['rn-bridge', 'cordova-bridge', 'bridge']) { try { bridge = require(n); break; } catch (_) {} }

const { Hub } = require('./core/hub');
const { createServer } = require('./core/server');

(async () => {
  const base = bridge && bridge.app && bridge.app.datadir ? bridge.app.datadir() : (process.env.TVHUB_DATA || __dirname);
  const dataDir = path.join(base, 'tvhub');
  fs.mkdirSync(dataDir, { recursive: true });
  const hub = new Hub(dataDir);
  const { url } = await createServer(hub, { dataDir, uiDir: path.join(__dirname, 'ui'), port: 17830 }).start();
  // الطريقتان: رسالة عبر الجسر + ملف يقرؤه الكود الأصلي
  fs.writeFileSync(path.join(dataDir, 'server.json'), JSON.stringify({ url }));
  if (bridge && bridge.channel) bridge.channel.send(JSON.stringify({ type: 'ready', url }));
  hub.start().catch((e) => console.error(e));
})();
