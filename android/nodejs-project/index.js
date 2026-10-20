'use strict';
// ────────────────────────────────────────────────────────────────
// محرك واتساب (يعمل داخل nodejs-mobile) — يتواصل مع التطبيق عبر HTTP محلي
// argv: node main.js <dataDir> <port> <token>
// ────────────────────────────────────────────────────────────────
const os = require('os');
try { os.networkInterfaces(); } catch (_) { os.networkInterfaces = () => ({}); }

// ── ترقيعات توافق Node 18 (nodejs-mobile) مع Baileys ──
// nodejs-mobile مبني بدون ICU: خيار fatal/ignoreBOM في TextDecoder غير مدعوم، فنتجاهله بدل الانهيار
(function patchTextDecoder() {
    const util = require('util');
    try { new util.TextDecoder('utf-8', { fatal: true, ignoreBOM: true }); return; } catch (_) {}
    const Orig = util.TextDecoder;
    class SafeTextDecoder extends Orig {
        constructor(enc, opts) {
            const o = Object.assign({}, opts || {});
            delete o.fatal; delete o.ignoreBOM;
            super(enc, o);
        }
    }
    globalThis.TextDecoder = SafeTextDecoder;
    util.TextDecoder = SafeTextDecoder;
})();
if (!globalThis.crypto) globalThis.crypto = require('crypto').webcrypto;
if (!Promise.withResolvers) {
    Promise.withResolvers = function () {
        let resolve, reject;
        const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
        return { promise, resolve, reject };
    };
}
if (!Array.prototype.toSorted) {
    Object.defineProperty(Array.prototype, 'toSorted', {
        value: function (fn) { return [...this].sort(fn); }, configurable: true, writable: true
    });
}
if (!Array.prototype.toReversed) {
    Object.defineProperty(Array.prototype, 'toReversed', {
        value: function () { return [...this].reverse(); }, configurable: true, writable: true
    });
}
if (!Array.prototype.findLast) {
    Object.defineProperty(Array.prototype, 'findLast', {
        value: function (fn) { for (let i = this.length - 1; i >= 0; i--) if (fn(this[i], i, this)) return this[i]; },
        configurable: true, writable: true
    });
}
// أي خطأ غير ملتقط يُسجَّل ولا يُنهي العملية (إنهاء Node يقتل التطبيق كله)
process.exit = (code) => { try { console.error('process.exit(' + code + ') blocked'); } catch (_) {} };

const fs = require('fs');
const path = require('path');
const http = require('http');
const { createTv, parseSegCaption, parseSegName } = require('./tvsession.js');
let P = null, baileys = {}, loadError = null;
try {
    P = require('pino');
    baileys = require('@whiskeysockets/baileys');
} catch (e) {
    loadError = e;
}

const makeWASocket = baileys.makeWASocket || baileys.default;
const {
    useMultiFileAuthState,
    downloadContentFromMessage,
    fetchLatestBaileysVersion,
    DisconnectReason = {}
} = baileys;

const DATA_DIR = process.argv[2];
const PORT = parseInt(process.argv[3], 10);
const TOKEN = process.argv[4];

const AUTH_DIR = path.join(DATA_DIR, 'auth');
const STAGE_DIR = path.join(DATA_DIR, 'staging');
const ERROR_LOG = path.join(DATA_DIR, 'node-errors.log');
fs.mkdirSync(STAGE_DIR, { recursive: true });
fs.mkdirSync(path.join(DATA_DIR, 'tmp'), { recursive: true });
if (loadError) { try { fs.appendFileSync(path.join(DATA_DIR, 'node-errors.log'), `[load] ${loadError.stack || loadError}\n`); } catch (_) {} }
process.on('exit', (c) => { try { fs.appendFileSync(path.join(DATA_DIR, 'node-errors.log'), `[exit] code=${c}\n`); } catch (_) {} });
process.env.TMPDIR = path.join(DATA_DIR, 'tmp');

// ───────────────────────── الإعدادات ─────────────────────────
const MAX_ATTEMPTS = 5;
const RETRY_BASE_MS = 3000;
const RETRY_MAX_MS = 60000;
const STALL_MS = 25000;
const ATTEMPT_MAX_MS = 30 * 60 * 1000;   // 30 دقيقة لمحاولة واحدة (الملفات الكبيرة)
const RECONNECT_BASE = 2000;
const RECONNECT_MAX = 60000;
const BOOT_TOLERANCE = 10;
const PAIR_MAX_TRIES = 6;

const cfg = {
    target: '',        // رقم المحادثة، فارغ = الكل
    gapMin: 3,         // ثوانٍ
    gapMax: 10,
    maxSizeMb: 0,      // 0 = بلا حد
    exts: [],          // فارغ = كل الأنواع
    mode: 'download',  // download | watch  (وضع التطبيق الحالي)
    chatCommands: true,
    watchGapMin: 1,    // فاصل التحميل في وضع المشاهدة (ثوانٍ)
    watchGapMax: 3
};

const BOOT_TIME = Math.floor(Date.now() / 1000);

// ───────────────────────── أوامر الشات + ترقيم الأجزاء (وضع المشاهدة) ─────────────────────────
const numRe = /(\d+)(?!.*\d)/;
function keyFor(name, tsMs) {
    const dot = name.lastIndexOf('.');
    const base = dot > 0 ? name.slice(0, dot) : name;
    const m = numRe.exec(base);
    const n = m && m[1].length <= 18 ? parseInt(m[1], 10) : NaN;
    if (Number.isFinite(n)) return { key: n, strict: n < 1e9 };
    return { key: tsMs, strict: false };
}

function parseChatCommand(text) {
    const t = String(text || '').trim();
    if (!/^[!\/]/.test(t)) return null;
    const parts = t.slice(1).trim().split(/\s+/);
    const w = (parts[0] || '').toLowerCase();
    const arg = parts[1];
    const map = {
        pause: 'pause', 'ايقاف': 'pause', 'إيقاف': 'pause', 'وقف': 'pause',
        play: 'play', resume: 'play', 'تشغيل': 'play', 'استئناف': 'play',
        next: 'next', skip: 'next', 'تخطي': 'next', 'تخطّي': 'next', 'التالي': 'next',
        fs: 'fs', fullscreen: 'fs', 'شاشة': 'fs', 'ملء': 'fs',
        speed: 'speed', 'سرعة': 'speed', 'تسريع': 'speed',
        vol: 'vol', volume: 'vol', 'صوت': 'vol',
        status: 'status', 'حالة': 'status'
    };
    const cmd = map[w];
    if (!cmd) return null;
    const n = arg !== undefined ? parseFloat(arg.replace(/[٠-٩]/g, (d) => '٠١٢٣٤٥٦٧٨٩'.indexOf(d))) : NaN;
    return { cmd, arg: Number.isFinite(n) ? n : null };
}


// ───────────────────────── أدوات ─────────────────────────
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (a, b) => a + Math.floor(Math.random() * (b - a + 1));

function toNum(v) {
    if (v == null) return 0;
    if (typeof v === 'object' && typeof v.toNumber === 'function') return v.toNumber();
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
}

function logError(err) {
    try { fs.appendFileSync(ERROR_LOG, `[${new Date().toISOString()}] ${err && err.stack || err}\n`); } catch (_) {}
}

function sanitizeFileName(name) {
    let n = String(name || '').replace(/[\/\\:*?"<>|\x00-\x1f]/g, '_').trim();
    if (!n) n = `file_${Date.now()}`;
    if (n.length > 120) {
        const ext = path.extname(n).slice(0, 15);
        n = n.slice(0, 120 - ext.length) + ext;
    }
    return n;
}

function safeUnlink(p) { try { if (fs.existsSync(p)) fs.unlinkSync(p); } catch (_) {} }

function freeBytes() {
    try {
        const s = fs.statfsSync(DATA_DIR);
        return Number(s.bavail) * Number(s.bsize);
    } catch (_) { return -1; }
}

function withTimeout(promise, ms, message) {
    let t;
    const timeout = new Promise((_, rej) => { t = setTimeout(() => rej(new Error(message)), ms); });
    return Promise.race([promise, timeout]).finally(() => clearTimeout(t));
}

function isExpiredError(err) {
    const code = err && err.output && err.output.statusCode || err && err.statusCode;
    const msg = String(err && err.message || '');
    return code === 404 || code === 410 || /\b(404|410)\b/.test(msg) || /empty media key|media key|expired/i.test(msg);
}

// ───────────────────────── قناة الأحداث (SSE) ─────────────────────────
const clients = new Set();
let conn = 'idle';
let pairingCode = '';

function emit(obj) {
    const line = `data: ${JSON.stringify(obj)}\n\n`;
    for (const res of clients) { try { res.write(line); } catch (_) {} }
}
function log(msg) { emit({ t: 'log', msg: String(msg) }); }
function setConn(c) { conn = c; emit({ t: 'state', conn: c, registered: isRegistered() }); }

process.on('uncaughtException', (e) => { logError(e); log('خطأ غير متوقع: ' + e.message); });
process.on('unhandledRejection', (e) => { logError(e); log('رفض غير متوقع: ' + (e && e.message || e)); });

function isRegistered() {
    try {
        const creds = JSON.parse(fs.readFileSync(path.join(AUTH_DIR, 'creds.json'), 'utf8'));
        return !!creds.registered;
    } catch (_) { return false; }
}

// ───────────────────────── المهام والطابور ─────────────────────────
const jobs = new Map();       // id -> job
const queue = [];             // jobs منتظرة بالترتيب
const seenIds = new Set();
let running = false;
let userPaused = false;
let netPaused = false;
let counter = 0;
const isPaused = () => userPaused || netPaused;

function pubJob(j) {
    return { id: j.id, name: j.name, size: j.size, from: j.from, status: j.status,
             done: j.done || 0, attempt: j.attempt || 0, error: j.error || '' };
}

function snapshot() {
    return { t: 'snapshot', conn, registered: isRegistered(), code: pairingCode,
             fatal: loadError ? `فشل تحميل مكتبة واتساب: ${loadError.message}` : '',
             paused: isPaused(), jobs: [...jobs.values()].map(pubJob) };
}

function enqueue(job) {
    jobs.set(job.id, job);
    queue.push(job);
    emit({ t: 'queued', ...pubJob(job) });
    processQueue();
}

/** وضع المشاهدة: الأقل رقماً أولاً (للأجزاء المرقّمة)، وإلا بترتيب الوصول */
function pickNext() {
    if (cfg.mode === 'watch') {
        queue.sort((a, b) => (a.strict && b.strict ? a.key - b.key : (a.seq || 0) - (b.seq || 0)));
    }
    return queue.shift();
}

async function processQueue() {
    if (running) return;
    running = true;
    try {
        while (!isPaused() && queue.length > 0) {
            const job = pickNext();
            if (job.status === 'canceled') continue;
            await handleJob(job);
            if (!isPaused() && queue.length > 0) {
                const gMin = cfg.mode === 'watch' ? cfg.watchGapMin : cfg.gapMin;
                const gMax = cfg.mode === 'watch' ? cfg.watchGapMax : cfg.gapMax;
                const lo = Math.max(0, gMin), hi = Math.max(lo, gMax);
                const gapMs = rand(lo * 1000, hi * 1000);
                const until = Date.now() + gapMs;
                while (Date.now() < until && !isPaused()) await sleep(300);
            }
        }
    } finally {
        running = false;
    }
}

function downloadWithProgress(job, stream, partPath) {
    return new Promise((resolve, reject) => {
        const out = fs.createWriteStream(partPath);
        let downloaded = 0, settled = false, stallTimer = null;
        let lastEmit = 0, lastBytes = 0, lastTime = Date.now(), speed = 0;

        const resetStall = () => {
            clearTimeout(stallTimer);
            stallTimer = setTimeout(() => fail(new Error('توقف التنزيل (لا استجابة)')), STALL_MS);
        };
        const fail = (err) => {
            if (settled) return;
            settled = true;
            clearTimeout(stallTimer);
            job.abort = null;
            try { stream.destroy(); } catch (_) {}
            out.destroy();
            reject(err);
        };
        job.abort = () => fail(new Error('canceled'));

        resetStall();
        stream.on('data', (chunk) => {
            downloaded += chunk.length;
            job.done = downloaded;
            resetStall();
            const now = Date.now();
            if (now - lastEmit >= 300) {
                const dt = (now - lastTime) / 1000;
                if (dt > 0) {
                    const inst = (downloaded - lastBytes) / dt;
                    speed = speed ? speed * 0.6 + inst * 0.4 : inst;
                }
                lastBytes = downloaded; lastTime = now; lastEmit = now;
                emit({ t: 'progress', id: job.id, done: downloaded, total: job.size, speed: Math.round(speed) });
            }
        });
        stream.on('error', fail);
        out.on('error', fail);
        out.on('finish', () => {
            if (settled) return;
            settled = true;
            clearTimeout(stallTimer);
            job.abort = null;
            resolve(downloaded);
        });
        stream.pipe(out);
    });
}

async function handleJob(job) {
    const partPath = path.join(STAGE_DIR, `${job.id}.part`);
    const finalPath = path.join(STAGE_DIR, `${job.id}.dl`);

    // مساحة كافية؟ (الملف يُنسخ لاحقاً للتنزيلات فنحتاج ضعف الحجم مؤقتاً)
    if (job.size > 0) {
        const free = freeBytes();
        if (free >= 0 && free < job.size * (cfg.mode === 'watch' ? 1.1 : 2.1)) {
            job.status = 'failed';
            job.error = 'مساحة غير كافية (يلزم ضعف حجم الملف مؤقتاً)';
            emit({ t: 'fail', id: job.id, error: job.error, final: true });
            return;
        }
    }

    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        if (job.status === 'canceled') { safeUnlink(partPath); return; }
        job.status = 'active';
        job.attempt = attempt;
        job.done = 0;
        emit({ t: 'start', id: job.id, attempt });

        try {
            const stream = await withTimeout(
                downloadContentFromMessage(job.media || job.docMsg, job.mediaType || 'document'),
                60000, 'انتهت مهلة بدء الاتصال بالملف'
            );
            await withTimeout(downloadWithProgress(job, stream, partPath), ATTEMPT_MAX_MS, 'انتهت مهلة التحميل');

            const actual = fs.statSync(partPath).size;
            if (actual === 0) throw new Error('الملف المحمّل فارغ');
            if (job.size > 0 && actual !== job.size) throw new Error(`الحجم غير مطابق (${actual} من ${job.size})`);

            fs.renameSync(partPath, finalPath);
            job.status = 'done';
            emit({ t: 'done', id: job.id, path: finalPath, name: job.name, size: actual, mime: job.mime });
            jobs.delete(job.id);
            return;
        } catch (err) {
            logError(err);
            safeUnlink(partPath);

            if (job.status === 'canceled' || (err && err.message === 'canceled')) {
                job.status = 'canceled';
                emit({ t: 'canceled', id: job.id });
                jobs.delete(job.id);
                return;
            }
            if (isExpiredError(err)) {
                job.status = 'expired';
                job.error = 'انتهت صلاحية الملف على خوادم واتساب';
                emit({ t: 'expired', id: job.id, error: job.error });
                return;
            }
            job.error = err.message;
            if (attempt < MAX_ATTEMPTS) {
                emit({ t: 'fail', id: job.id, error: err.message, final: false, attempt });
                const wait = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * 2 ** (attempt - 1)) + rand(0, 1500);
                const until = Date.now() + wait;
                while (Date.now() < until && job.status !== 'canceled') await sleep(300);
            }
        }
    }
    job.status = 'failed';
    emit({ t: 'fail', id: job.id, error: job.error || 'فشل', final: true });
}


// ───────────────────────── وضع التلفاز (tv): أجزاء TS متصلة من البوت ─────────────────────────
const TV_DIR = path.join(DATA_DIR, 'tv');
const TV_PARALLEL = 2;          // تحميل جزأين معاً (الجزء التالي جاهز قبل أن ينتهي المشغّل من الحالي)
const TV_MAX_ATTEMPTS = 6;
fs.mkdirSync(TV_DIR, { recursive: true });
const tvQueue = [];
let tvActive = 0;
let tvCounter = 0;

async function tvSendText(text) {
    if (!currentSock) throw new Error('no socket');
    if (!cfg.target) { emit({ t: 'error', msg: 'حدّد رقم البوت في إعدادات التلفاز أولاً' }); throw new Error('no target'); }
    await currentSock.sendMessage(`${cfg.target}@s.whatsapp.net`, { text });
}

const tv = createTv({ sendText: tvSendText, emit: (o) => emit(o) });

function tvPad(n) { return String(n).padStart(6, '0'); }

async function runTvJob(job) {
    const dir = path.join(TV_DIR, job.sid);
    fs.mkdirSync(dir, { recursive: true });
    const part = path.join(dir, `seg_${tvPad(job.n)}.part`);
    const fin = path.join(dir, `seg_${tvPad(job.n)}.ts`);
    const jobLike = { id: `tv${job.sid}${job.n}`, size: job.size, done: 0, abort: null };
    let lastErr = '';

    for (let attempt = 1; attempt <= TV_MAX_ATTEMPTS; attempt++) {
        if (!tv.isCurrent(job.sid)) { safeUnlink(part); return; }
        try {
            const stream = await withTimeout(downloadContentFromMessage(job.docMsg, 'document'), 45000, 'انتهت مهلة بدء الاتصال بالجزء');
            await withTimeout(downloadWithProgress(jobLike, stream, part), 4 * 60 * 1000, 'انتهت مهلة تحميل الجزء');
            const actual = fs.statSync(part).size;
            if (actual === 0) throw new Error('الجزء فارغ');
            if (job.size > 0 && actual !== job.size) throw new Error(`الحجم غير مطابق (${actual}/${job.size})`);
            fs.renameSync(part, fin);
            tv.onSegDone({ sid: job.sid, n: job.n, d: job.d, disc: job.disc, path: fin });
            return;
        } catch (err) {
            lastErr = err && err.message || String(err);
            logError(err);
            safeUnlink(part);
            if (isExpiredError(err)) break;                       // لا فائدة من إعادة التحميل: نطلبه من البوت
            if (attempt < TV_MAX_ATTEMPTS) await sleep(Math.min(15000, 1500 * 2 ** (attempt - 1)));
        }
    }
    log(`فشل تحميل الجزء ${job.n}: ${lastErr}`);
    tv.onSegFailed({ sid: job.sid, n: job.n });
}

function tvPump() {
    while (tvActive < TV_PARALLEL && tvQueue.length) {
        tvQueue.sort((a, b) => a.n - b.n);                        // الأقل رقماً أولاً دائماً
        const job = tvQueue.shift();
        tvActive++;
        runTvJob(job).catch(logError).finally(() => { tvActive--; tvPump(); });
    }
}

function tvCleanOld(keepSid) {
    try {
        for (const d of fs.readdirSync(TV_DIR)) {
            if (d !== keepSid) fs.rmSync(path.join(TV_DIR, d), { recursive: true, force: true });
        }
    } catch (_) {}
}

// ───────────────────────── واتساب ─────────────────────────
let currentSock = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
let pairingPhone = null;
let pairTries = 0;
let stopReconnect = false;
let pairTimer = null;

function destroySock(s) {
    if (!s) return;
    for (const ev of ['connection.update', 'messages.upsert', 'creds.update']) {
        try { s.ev.removeAllListeners(ev); } catch (_) {}
    }
    try { s.end(undefined); } catch (_) {}
}

function scheduleReconnect(fast = false) {
    if (reconnectTimer || stopReconnect) return;
    reconnectAttempts++;
    const delayMs = fast ? 1000 : Math.min(RECONNECT_MAX, RECONNECT_BASE * 2 ** (reconnectAttempts - 1)) + rand(0, 1000);
    setConn('reconnecting');
    log(`إعادة الاتصال بعد ${(delayMs / 1000).toFixed(1)}ث (محاولة ${reconnectAttempts})`);
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect().catch((e) => { logError(e); scheduleReconnect(); });
    }, delayMs);
}

function matchesTarget(msg) {
    const target = cfg.target;
    if (!target) return true;
    if (msg.key.fromMe) return true;
    const jids = [msg.key.remoteJid, msg.key.remoteJidAlt, msg.key.participant, msg.key.participantAlt].filter(Boolean);
    if (jids.some((j) => j.includes(target))) return true;
    return jids.some((j) => j.endsWith('@lid'));
}

function extractDocument(message) {
    let m = message;
    for (let i = 0; i < 4 && m; i++) {
        if (m.ephemeralMessage) m = m.ephemeralMessage.message;
        else if (m.viewOnceMessage) m = m.viewOnceMessage.message;
        else if (m.viewOnceMessageV2) m = m.viewOnceMessageV2.message;
        else if (m.documentWithCaptionMessage) m = m.documentWithCaptionMessage.message;
        else break;
    }
    return (m && m.documentMessage) || null;
}

async function connect() {
    destroySock(currentSock);
    stopReconnect = false;
    setConn('connecting');

    const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
    let version;
    try { ({ version } = await fetchLatestBaileysVersion()); } catch (e) { logError(e); }

    const sock = makeWASocket({
        auth: state,
        ...(version ? { version } : {}),
        printQRInTerminal: false,
        browser: ['Ubuntu', 'Chrome', '20.0.04'],
        syncFullHistory: false,
        markOnlineOnConnect: false,
        connectTimeoutMs: 60000,
        keepAliveIntervalMs: 25000,
        logger: P({ level: 'silent' })
    });
    currentSock = sock;
    const isCurrent = () => sock === currentSock;

    sock.ev.on('creds.update', saveCreds);

    let codeRequested = false;
    async function requestCode() {
        if (codeRequested || !pairingPhone || sock.authState.creds.registered) return;
        codeRequested = true;
        try {
            await sleep(1500);
            if (!isCurrent()) return;
            const code = await sock.requestPairingCode(pairingPhone);
            pairingCode = code && code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : String(code);
            clearTimeout(pairTimer);
            emit({ t: 'code', code: pairingCode });
            log('تم الحصول على رمز الربط — أدخله في واتساب');
            setConn('pairing');
        } catch (err) {
            logError(err);
            pairTries++;
            log(`فشل طلب الرمز (${pairTries}/${PAIR_MAX_TRIES}): ${err.message}`);
            if (pairTries >= PAIR_MAX_TRIES) {
                pairingPhone = null;
                emit({ t: 'error', msg: 'تعذّر الحصول على رمز الربط. تحقق من الإنترنت والرقم ثم أعد المحاولة.' });
                setConn('needpair');
                return;
            }
            try { sock.end(undefined); } catch (_) {}
            if (isCurrent()) scheduleReconnect();
        }
    }

    sock.ev.on('connection.update', (update) => {
        if (!isCurrent()) return;
        const { connection, lastDisconnect, qr } = update;
        if (qr) { log('وصل الاتصال بواتساب — جارٍ طلب رمز الربط'); requestCode(); }

        if (connection === 'open') {
            reconnectAttempts = 0;
            pairingPhone = null;
            pairingCode = '';
            setConn('open');
        }

        if (connection === 'close') {
            const code = lastDisconnect && lastDisconnect.error && lastDisconnect.error.output
                ? lastDisconnect.error.output.statusCode : undefined;
            log(`انقطع الاتصال: code=${code} ${(lastDisconnect && lastDisconnect.error && lastDisconnect.error.message) || ''}`);

            if (code === DisconnectReason.loggedOut) {
                try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); } catch (_) {}
                if (pairingPhone && pairTries < PAIR_MAX_TRIES) {
                    pairTries++;
                    log(`لم يُقبل الرمز أو انتهت صلاحيته (${pairTries}/${PAIR_MAX_TRIES}) — رمز جديد...`);
                    scheduleReconnect(true);
                    return;
                }
                pairingPhone = null;
                setConn('loggedout');
                return;
            }
            if (code === DisconnectReason.connectionReplaced) {
                stopReconnect = true;
                setConn('replaced');
                return;
            }
            scheduleReconnect(code === DisconnectReason.restartRequired);
        }
    });

    sock.ev.on('messages.upsert', ({ messages, type }) => {
        if (!isCurrent() || type !== 'notify') return;
        const sorted = [...messages].sort((a, b) => toNum(a.messageTimestamp) - toNum(b.messageTimestamp));

        for (const msg of sorted) {
            if (!msg.message) continue;
            const ts = toNum(msg.messageTimestamp);
            if (ts && ts < BOOT_TIME - BOOT_TOLERANCE) continue;
            if (!matchesTarget(msg)) continue;

            const watch = cfg.mode === 'watch';
            let m = msg.message;
            for (let i = 0; i < 4 && m; i++) {
                if (m.ephemeralMessage) m = m.ephemeralMessage.message;
                else if (m.viewOnceMessage) m = m.viewOnceMessage.message;
                else if (m.viewOnceMessageV2) m = m.viewOnceMessageV2.message;
                else if (m.documentWithCaptionMessage) m = m.documentWithCaptionMessage.message;
                else break;
            }
            m = m || {};

            const mid = msg.key.id;
            if (mid) {
                if (seenIds.has(mid)) continue;
                seenIds.add(mid);
            }

            // ⓪ وضع التلفاز: نصوص بروتوكول البوت + أجزاء TS (فقط من رقم البوت المحدد)
            if (cfg.mode === 'tv') {
                if (!cfg.target || msg.key.fromMe) continue;
                const ttext = m.conversation || (m.extendedTextMessage && m.extendedTextMessage.text) || '';
                if (ttext.startsWith('TV|')) { tv.onText(ttext); continue; }
                const doc = m.documentMessage;
                if (doc) {
                    const nm = parseSegName(doc.fileName);
                    if (nm && tv.isCurrent(nm.sid)) {
                        const cap = parseSegCaption(doc.caption || '') || { d: 30, disc: 0 };
                        tv.onSegSeen({ sid: nm.sid, n: nm.n });
                        tvQueue.push({ sid: nm.sid, n: nm.n, d: cap.d, disc: cap.disc, docMsg: doc, size: toNum(doc.fileLength), seq: tvCounter++ });
                        tvPump();
                    }
                }
                continue;
            }

            // ① أوامر نصية من الشات (وضع المشاهدة فقط، من حسابك أو الرقم المحدد)
            if (watch && cfg.chatCommands) {
                const text = m.conversation || (m.extendedTextMessage && m.extendedTextMessage.text) || '';
                const c = text ? parseChatCommand(text) : null;
                if (c && (msg.key.fromMe || cfg.target)) { emit({ t: 'chatCmd', cmd: c.cmd, arg: c.arg }); continue; }
            }

            // ② الأجزاء: ملف (دائماً) أو فيديو (وضع المشاهدة)
            let media = null, mediaType = '', name = '', size = 0, mime = '';
            if (m.documentMessage) {
                media = m.documentMessage; mediaType = 'document'; mime = media.mimetype || '';
                name = sanitizeFileName(media.fileName || `file_${Date.now()}`);
                size = toNum(media.fileLength);
            } else if (watch && m.videoMessage) {
                media = m.videoMessage; mediaType = 'video'; mime = media.mimetype || 'video/mp4';
                const cap = String(media.caption || '').trim();
                name = sanitizeFileName((cap || `video_${Date.now()}`).replace(/\s+/g, '_'));
                if (!/\.[a-z0-9]{2,4}$/i.test(name)) name += '.mp4';
                size = toNum(media.fileLength);
            }
            if (!media) continue;

            const id = `${Date.now().toString(36)}${(counter++).toString(36)}`;
            const from = (msg.key.remoteJid || '').split('@')[0];
            const kf = keyFor(name, (ts || Math.floor(Date.now() / 1000)) * 1000);
            const job = { id, media, mediaType, docMsg: media, name, size, from, mime, key: kf.key, strict: kf.strict, seq: counter,
                          status: 'queued', done: 0, attempt: 0, error: '' };

            if (!watch) {
                const ext = path.extname(name).slice(1).toLowerCase();
                if (cfg.exts.length && !cfg.exts.includes(ext)) {
                    job.status = 'skipped'; job.error = 'نوع الملف غير مسموح';
                    jobs.set(id, job); emit({ t: 'queued', ...pubJob(job) }); continue;
                }
                if (cfg.maxSizeMb > 0 && size > cfg.maxSizeMb * 1048576) {
                    job.status = 'skipped'; job.error = `أكبر من ${cfg.maxSizeMb} MB`;
                    jobs.set(id, job); emit({ t: 'queued', ...pubJob(job) }); continue;
                }
            }
            enqueue(job);
        }
    });
}

// ───────────────────────── فحص الاتصال ─────────────────────────
async function runDiag() {
    log(`— فحص —  Node ${process.version} ${process.platform}/${process.arch}`);
    log('الوقت: ' + new Date().toISOString());
    const dns = require('dns');
    try {
        const addr = await new Promise((res, rej) => dns.lookup('web.whatsapp.com', (e, a) => (e ? rej(e) : res(a))));
        log('DNS ✓ ' + addr);
    } catch (e) { log('DNS ✗ ' + (e.code || '') + ' ' + e.message); }

    try {
        await new Promise((res, rej) => {
            const tls = require('tls');
            const sock = tls.connect({ host: 'web.whatsapp.com', port: 443, servername: 'web.whatsapp.com' }, () => {
                log(`TLS ✓ ${sock.getProtocol()} authorized=${sock.authorized}${sock.authorizationError ? ' ' + sock.authorizationError : ''}`);
                sock.end(); res();
            });
            sock.on('error', rej);
            sock.setTimeout(10000, () => sock.destroy(new Error('timeout')));
        });
    } catch (e) { log('TLS ✗ ' + e.message); }

    try {
        const v = await withTimeout(fetchLatestBaileysVersion(), 15000, 'timeout');
        log('نسخة واتساب: ' + JSON.stringify(v).slice(0, 160));
    } catch (e) { log('جلب النسخة ✗ ' + e.message); }
    log('— انتهى الفحص —');
}

// ───────────────────────── الأوامر من التطبيق ─────────────────────────
async function handleCmd(c) {
    if (loadError && ['connect', 'pair', 'diag'].includes(c.cmd)) {
        emit({ t: 'error', msg: `فشل تحميل مكتبة واتساب: ${loadError.message}` });
        return;
    }
    switch (c.cmd) {
        case 'connect':
            if (currentSock) { emit(snapshot()); break; }
            if (isRegistered()) await connect();
            else setConn('needpair');
            break;

        case 'pair': {
            const phone = String(c.phone || '').replace(/[^0-9]/g, '');
            if (phone.length < 8) { emit({ t: 'error', msg: 'رقم غير صالح' }); break; }
            destroySock(currentSock); currentSock = null;
            clearTimeout(reconnectTimer); reconnectTimer = null;
            try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); } catch (_) {}
            pairingPhone = phone; pairTries = 0; pairingCode = ''; reconnectAttempts = 0;
            clearTimeout(pairTimer);
            pairTimer = setTimeout(() => {
                if (pairingPhone && !pairingCode) {
                    emit({ t: 'error', msg: 'لم يتم الاتصال بواتساب خلال 40 ثانية. اضغط «فحص الاتصال» لمعرفة السبب.' });
                }
            }, 40000);
            log('بدء الربط…');
            await connect();
            break;
        }

        case 'config':
            cfg.target = String(c.target || '').replace(/[^0-9]/g, '');
            cfg.gapMin = Number(c.gapMin) || 0;
            cfg.gapMax = Number(c.gapMax) || 0;
            cfg.maxSizeMb = Number(c.maxSizeMb) || 0;
            cfg.exts = String(c.exts || '').toLowerCase().split(/[,\s]+/).map((s) => s.replace(/^\./, '')).filter(Boolean);
            if (c.chatCommands !== undefined) cfg.chatCommands = !!c.chatCommands;
            if (c.watchGapMin !== undefined) cfg.watchGapMin = Number(c.watchGapMin) || 0;
            if (c.watchGapMax !== undefined) cfg.watchGapMax = Number(c.watchGapMax) || 0;
            break;

        case 'setMode':
            cfg.mode = c.mode === 'watch' ? 'watch' : c.mode === 'tv' ? 'tv' : 'download';
            break;

        case 'setPause':
            userPaused = !!c.user; netPaused = !!c.net;
            emit({ t: 'paused', paused: isPaused() });
            if (!isPaused()) processQueue();
            break;

        case 'cancel': {
            const j = jobs.get(c.id);
            if (!j) break;
            const wasActive = j.status === 'active';
            j.status = 'canceled';
            if (wasActive && j.abort) j.abort();
            else { emit({ t: 'canceled', id: j.id }); jobs.delete(j.id); }
            break;
        }

        case 'retry': {
            const j = jobs.get(c.id);
            if (!j || (j.status !== 'failed' && j.status !== 'expired')) break;
            j.status = 'queued'; j.error = ''; j.attempt = 0;
            emit({ t: 'queued', ...pubJob(j) });
            queue.push(j);
            processQueue();
            break;
        }

        case 'clearJob':
            jobs.delete(c.id);
            break;

        case 'tvStart':
            if (!/^[a-z0-9]{4,12}$/.test(String(c.sid || '')) || !/^https?:\/\//i.test(String(c.url || ''))) {
                emit({ t: 'error', msg: 'رابط القناة غير صالح' });
                break;
            }
            tvQueue.length = 0;
            tvCleanOld(c.sid);
            tv.start({ sid: c.sid, url: c.url, seg: Number(c.seg) || 30 });
            break;

        case 'tvStop':
            tvQueue.length = 0;
            tv.stop(c.sid, c.reason || 'user');
            break;

        case 'diag':
            await runDiag();
            break;

        case 'logout':
            try { if (currentSock) await currentSock.logout(); } catch (_) {}
            destroySock(currentSock); currentSock = null;
            clearTimeout(reconnectTimer); reconnectTimer = null;
            try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); } catch (_) {}
            pairingPhone = null; pairingCode = '';
            setConn('needpair');
            break;

        default: break;
    }
}

// ───────────────────────── خادم HTTP المحلي ─────────────────────────
const server = http.createServer((req, res) => {
    if (req.headers['x-token'] !== TOKEN) { res.writeHead(403); return res.end(); }

    if (req.method === 'GET' && req.url === '/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        res.write(':ok\n\n');
        clients.add(res);
        req.on('close', () => clients.delete(res));
        res.write(`data: ${JSON.stringify(snapshot())}\n\n`);
        return;
    }

    if (req.method === 'POST' && req.url === '/cmd') {
        let body = '';
        req.on('data', (d) => { body += d; if (body.length > 1e6) req.destroy(); });
        req.on('end', () => {
            res.writeHead(200); res.end('ok');
            try { handleCmd(JSON.parse(body)).catch((e) => { logError(e); log('خطأ أمر: ' + e.message); }); }
            catch (e) { logError(e); }
        });
        return;
    }
    res.writeHead(404); res.end();
});
server.listen(PORT, '127.0.0.1', () => log('محرك واتساب جاهز'));
setInterval(() => { for (const r of clients) { try { r.write(':hb\n\n'); } catch (_) {} } }, 15000);
