'use strict';
// بديل بلا Electron (لينكس/ويندوز): node serve.js ثم افتح الرابط في المتصفح
const path = require('path');
const os = require('os');
const { Hub } = require('./core/hub');
const { createServer } = require('./core/server');

(async () => {
  const dataDir = process.env.TVHUB_DATA || path.join(os.homedir(), '.tv-hub');
  const hub = new Hub(dataDir);
  const { url } = await createServer(hub, { dataDir, uiDir: path.join(__dirname, 'ui'), port: +process.env.PORT || 0, token: process.env.TVHUB_TOKEN }).start();
  console.log('افتح هذا الرابط في المتصفح:\n' + url);
  hub.start().catch((e) => console.error(e));
  process.on('SIGINT', async () => { await Promise.race([hub.stop(), new Promise((r) => setTimeout(r, 3000))]); process.exit(0); });
})();
