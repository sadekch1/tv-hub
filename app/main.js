'use strict';
// Electron: Windows + Linux
const { app, BrowserWindow, shell } = require('electron');
const path = require('path');
const { Hub } = require('./core/hub');
const { createServer } = require('./core/server');

if (process.platform === 'linux') app.commandLine.appendSwitch('no-sandbox'); // يلزم لـ AppImage على بعض التوزيعات
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) app.quit();

let hub, quitting = false;

app.whenReady().then(async () => {
  const dataDir = app.getPath('userData');
  hub = new Hub(dataDir);
  const { url } = await createServer(hub, { dataDir, uiDir: path.join(__dirname, 'ui') }).start();
  hub.start().catch((e) => console.error(e));

  const win = new BrowserWindow({
    width: 1240, height: 780, minWidth: 720, minHeight: 520,
    backgroundColor: '#0f1322', autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false },
  });
  win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(({ url: u }) => { shell.openExternal(u); return { action: 'deny' }; });
  win.loadURL(url);
});

// عند الإغلاق نوقف البث على الخادم حتى لا يستمر الرفع
app.on('before-quit', (e) => {
  if (hub && hub.playing && !quitting) {
    e.preventDefault(); quitting = true;
    Promise.race([hub.stop(), new Promise((r) => setTimeout(r, 3000))]).finally(() => app.quit());
  }
});
app.on('window-all-closed', () => app.quit());
