'use strict';
// قلب التطبيق: يتصل بواتساب كجهاز مرتبط، يرسل أوامر البوت، ويستقبل أجزاء البث.
// يعمل كما هو على Windows / Linux (Electron أو Node) وعلى Android (nodejs-mobile).
const EventEmitter = require('events');
const fs = require('fs');
const path = require('path');
const { pipeline } = require('stream/promises');
const pino = require('pino');
const B = require('@whiskeysockets/baileys');

const makeWASocket = B.default;
const { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion, downloadContentFromMessage, makeCacheableSignalKeyStore } = B;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const DEFAULTS = { botNumber: '', bufferMin: 5, segMin: 1, totalMin: 60 };

class Hub extends EventEmitter {
  constructor(dataDir) {
    super();
    this.dir = dataDir;
    this.partsDir = path.join(dataDir, 'parts');
    this.settingsFile = path.join(dataDir, 'settings.json');
    fs.mkdirSync(dataDir, { recursive: true });
    this.settings = { ...DEFAULTS };
    try { Object.assign(this.settings, JSON.parse(fs.readFileSync(this.settingsFile, 'utf8'))); } catch (_) {}
    this.conn = 'connecting'; // connecting | open | needs-pair
    this.playing = null;
    this.parts = new Set();
    this.counter = 0;
    this.gen = 0;
    this.sock = null;
    this.resetParts();
  }

  // ---------- الحالة والإعدادات ----------
  getState() {
    return {
      conn: this.conn,
      playing: this.playing,
      parts: [...this.parts].sort((a, b) => a - b),
      settings: this.settings,
    };
  }
  setConn(c) { this.conn = c; this.emit('status'); }

  saveSettings(p) {
    const s = this.settings;
    if (p.botNumber !== undefined) s.botNumber = String(p.botNumber).replace(/\D/g, '');
    if (p.bufferMin !== undefined) s.bufferMin = [3, 5, 10].includes(+p.bufferMin) ? +p.bufferMin : 5;
    if (p.segMin !== undefined) s.segMin = Math.min(5, Math.max(1, Math.round(+p.segMin) || 1));
    if (p.totalMin !== undefined) s.totalMin = Math.min(240, Math.max(5, Math.round(+p.totalMin) || 60));
    fs.writeFileSync(this.settingsFile, JSON.stringify(s, null, 2));
    this.emit('status');
    return s;
  }

  // ---------- الاتصال بواتساب ----------
  async start() {
    const { state, saveCreds } = await useMultiFileAuthState(path.join(this.dir, 'session'));
    let version;
    try { ({ version } = await fetchLatestBaileysVersion()); } catch (_) {}
    const sock = makeWASocket({
      version,
      logger: pino({ level: 'silent' }),
      printQRInTerminal: false,
      browser: ['Ubuntu', 'Chrome', '20.0.04'],
      auth: { creds: state.creds, keys: makeCacheableSignalKeyStore(state.keys, pino({ level: 'fatal' })) },
      markOnlineOnConnect: false,
      syncFullHistory: false,
      generateHighQualityLinkPreview: false,
    });
    this.sock = sock;
    if (!state.creds.registered) this.setConn('needs-pair');

    sock.ev.on('creds.update', saveCreds);
    sock.ev.on('connection.update', async ({ connection, lastDisconnect }) => {
      if (connection === 'open') this.setConn('open');
      if (connection === 'close') {
        if (sock !== this.sock) return;
        const code = lastDisconnect?.error?.output?.statusCode;
        if (code === DisconnectReason.loggedOut) {
          fs.rmSync(path.join(this.dir, 'session'), { recursive: true, force: true });
          this.setConn('needs-pair');
        } else if (this.conn === 'open') {
          this.setConn('connecting');
        }
        await sleep(3000);
        this.start().catch((e) => this.emit('log', e.message));
      }
    });
    sock.ev.on('messages.upsert', ({ messages }) => {
      for (const m of messages) this.onMessage(m).catch((e) => this.emit('log', e.message));
    });
  }

  // طلب رمز الإقران (واتساب ← الأجهزة المرتبطة ← الربط برقم الهاتف)
  async pair(phone) {
    const digits = String(phone || '').replace(/\D/g, '');
    if (digits.length < 8) throw new Error('أدخل رقمك مع رمز الدولة، مثال: 213555000111');
    if (!this.sock) throw new Error('الاتصال لم يبدأ بعد، أعد المحاولة بعد ثوانٍ');
    await sleep(1500);
    const code = await this.sock.requestPairingCode(digits);
    return (code.match(/.{1,4}/g) || [code]).join('-');
  }

  // ---------- التواصل مع البوت ----------
  botDigits() { return this.settings.botNumber; }
  isBot(jid) {
    const d = this.botDigits();
    return !!d && String(jid).split('@')[0].split(':')[0] === d;
  }
  async send(text) {
    if (!this.botDigits()) throw new Error('أدخل رقم البوت في الإعدادات أولاً');
    if (this.conn !== 'open' || !this.sock) throw new Error('غير متصل بواتساب بعد');
    await this.sock.sendMessage(this.botDigits() + '@s.whatsapp.net', { text });
  }

  async play(ch) {
    if (!/^https?:\/\/\S+$/i.test(ch.url || '')) throw new Error('رابط القناة غير صالح');
    if (this.playing) { await this.stop(); await sleep(2500); }
    this.gen++;
    this.counter = 0;
    this.resetParts();
    this.playing = { name: ch.name };
    this.emit('status');
    try {
      const s = this.settings;
      await this.send(`.playm3u ${ch.url} ${s.totalMin} ${s.segMin}`);
    } catch (e) {
      this.playing = null;
      this.emit('status');
      throw e;
    }
  }

  async stop() {
    const was = this.playing;
    this.playing = null;
    this.gen++;
    this.resetParts();
    this.emit('status');
    if (was) { try { await this.send('.stopplaym3u'); } catch (_) {} }
  }

  // ---------- الرسائل الواردة ----------
  async onMessage(m) {
    if (!m.message || m.key.fromMe) return;
    const jid = m.key.remoteJid || '';
    if (jid.endsWith('@g.us') || jid === 'status@broadcast') return;
    const msg = m.message;
    const text = msg.conversation || msg.extendedTextMessage?.text;
    const doc = msg.documentMessage || msg.documentWithCaptionMessage?.message?.documentMessage;
    const fromBot = this.isBot(jid);

    if (text && fromBot) {
      this.emit('bot', text);
      // خطأ من البوت قبل وصول أي جزء: نحرّر الواجهة
      if (text.startsWith('❌') && this.playing && this.parts.size === 0) {
        this.playing = null;
        this.gen++;
        this.emit('status');
      }
      return;
    }
    if (!doc || !this.playing) return;
    const name = doc.fileName || '';
    const cap = doc.caption || '';
    // بعض الحسابات تصل بمعرّف @lid بدل الرقم؛ نقبلها فقط إن بدا الملف جزءاً من البث
    const looksPart = /_part\d+\./i.test(name) || /الجزء/.test(cap);
    if (!fromBot && !(jid.endsWith('@lid') && looksPart)) return;
    await this.savePart(doc, name, cap);
  }

  async savePart(doc, name, cap) {
    const gen = this.gen;
    let n = parseInt((/_part(\d+)\./i.exec(name) || /الجزء\s*(\d+)/.exec(cap) || [])[1], 10);
    if (!n) n = this.counter + 1;
    this.counter = Math.max(this.counter, n);
    const final = this.partPath(n);
    const tmp = final + '.tmp';
    for (let a = 1; a <= 3; a++) {
      try {
        const stream = await downloadContentFromMessage(doc, 'document');
        await pipeline(stream, fs.createWriteStream(tmp));
        break;
      } catch (e) {
        if (a === 3) { fs.rmSync(tmp, { force: true }); throw e; }
        await sleep(1500 * a);
      }
    }
    if (gen !== this.gen || !this.playing) { fs.rmSync(tmp, { force: true }); return; }
    fs.renameSync(tmp, final);
    this.parts.add(n);
    this.emit('part', { n, size: fs.statSync(final).size });
  }

  // ---------- ملفات الأجزاء ----------
  partPath(n) { return path.join(this.partsDir, n + '.mp4'); }
  resetParts() {
    fs.rmSync(this.partsDir, { recursive: true, force: true });
    fs.mkdirSync(this.partsDir, { recursive: true });
    this.parts.clear();
  }
  // حذف الأجزاء التي انتهى تشغيلها لتوفير المساحة
  prune(current) {
    for (const k of [...this.parts]) {
      if (k < current - 1) { fs.rmSync(this.partPath(k), { force: true }); this.parts.delete(k); }
    }
  }
}

module.exports = { Hub };
