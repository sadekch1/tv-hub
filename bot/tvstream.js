/**
 * ============================================================
 * TV Stream — وحدة بث مستمر مخصّصة لتطبيق التلفاز (مستقلة عن m3uplayer.js)
 * ============================================================
 * الفكرة: ffmpeg ينسخ البث (-c copy) إلى أجزاء TS متصلة الزمن، وقائمة إرسال
 * تعيد المحاولة، ونبضات من التطبيق تحرس الجلسة، وإيقاف فوري.
 *
 * البروتوكول (نص عادي، يقرأه التطبيق):
 *   جزء فيديو : ملف  tv_<sid>_<000123>.ts   وcaption = TV|<sid>|seg|{"n":123,"d":30.1,"disc":0}
 *   حدث نصي   : TV|<sid>|<event>|<json>
 *   الأحداث   : start, src_ok, src_fail, warn, gone, end, stopped, err
 *
 * الأوامر (تُربط في index.js):
 *   .tvplay <sid> <url> [segSec]   .tvstop <sid|all>   .tvping <sid>
 *   .tvresend <sid> <n,n,...>      .tvstatus
 * ============================================================
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const CFG = {
    segmentSec: 30,
    uploadConcurrency: 2,
    maxAttempts: 12,
    retryBaseMs: 2000,
    retryMaxMs: 30000,
    watchdogMs: 150000,          // 3 نبضات مفقودة (النبضة كل 45 ثانية)
    keepBytes: 1.5 * 1024 * 1024 * 1024,
    keepMinSegments: 6,
    maxSessionMs: 12 * 60 * 60 * 1000,
    maxSourceRestarts: 40,       // محاولات متتالية بدون أي تقدّم
    probeTimeoutMs: 15000,
    slowQueueThreshold: 4,
    slowWarnEveryMs: 120000,
    userAgent: 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36'
};

const sessions = new Map(); // chatId -> session


// ── من يحق له تشغيل أوامر .tv*؟ (حسابك أنت fromMe، أو أرقام في TV_ALLOWED / data/tv_allowed.json) ──
function loadAllowed() {
    const out = new Set();
    String(process.env.TV_ALLOWED || '').split(/[,;\n]+/).forEach(x => { const d = x.replace(/\D/g, ''); if (d.length >= 7) out.add(d); });
    try {
        const f = path.join(process.cwd(), 'data', 'tv_allowed.json');
        if (fs.existsSync(f)) JSON.parse(fs.readFileSync(f, 'utf8')).forEach(x => { const d = String(x).replace(/\D/g, ''); if (d.length >= 7) out.add(d); });
    } catch (_) {}
    return out;
}
function isAllowedSender(key) {
    if (!key) return false;
    if (key.fromMe) return true;
    if (String(key.remoteJid || '').endsWith('@g.us')) return false;   // لا مجموعات إطلاقاً
    const allowed = loadAllowed();
    if (!allowed.size) return false;                       // لا قائمة = لا أحد غير حسابك
    const jids = [key.remoteJid, key.remoteJidAlt, key.participant, key.participantAlt].filter(Boolean);
    return jids.some(j => {
        if (String(j).endsWith('@g.us')) return false;     // لا مجموعات
        const num = String(j).split('@')[0].split(':')[0].replace(/\D/g, '');
        return num && allowed.has(num);
    });
}

// ── أدوات ──
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const isValidSid = (s) => typeof s === 'string' && /^[a-z0-9]{4,12}$/.test(s);
const isValidUrl = (u) => typeof u === 'string' && /^https?:\/\//i.test(u.trim());
const pad = (n) => String(n).padStart(6, '0');

async function sendEvent(sess, event, data = {}) {
    try {
        await sess.sock.sendMessage(sess.chatId, { text: `TV|${sess.id}|${event}|${JSON.stringify(data)}` });
    } catch (_) { /* الأحداث النصية لا تُعاد */ }
}

function probeSource(url, timeoutMs) {
    return new Promise((resolve) => {
        let done = false;
        const finish = (v) => { if (!done) { done = true; resolve(v); } };
        const args = ['-v', 'error', '-user_agent', CFG.userAgent, '-analyzeduration', '3000000',
            '-probesize', '3000000', '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', url];
        let out = '';
        const p = spawn('ffprobe', args, { stdio: ['ignore', 'pipe', 'ignore'] });
        const timer = setTimeout(() => { try { p.kill('SIGKILL'); } catch (_) {} finish(false); }, timeoutMs);
        p.stdout.on('data', d => { out += d.toString(); });
        p.on('close', (code) => { clearTimeout(timer); finish(code === 0 && /video|audio/.test(out)); });
        p.on('error', () => { clearTimeout(timer); finish(false); });
    });
}

// ── بناء أوامر ffmpeg ──
function buildFfmpegArgs(sess, startNumber) {
    const base = ['-hide_banner', '-loglevel', 'warning', '-nostdin'];
    let input;
    if (sess.opts.sourceArgs) {
        input = sess.opts.sourceArgs;                         // للاختبار فقط
    } else {
        input = [];
        if (/^https?:/i.test(sess.url)) {
            input.push('-user_agent', CFG.userAgent,
                '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '5',
                '-rw_timeout', '15000000');
        }
        input.push('-fflags', '+genpts+discardcorrupt', '-i', sess.url, '-map', '0:v:0?', '-map', '0:a?');
    }
    const codec = sess.opts.codecArgs || ['-c', 'copy'];
    const out = [
        '-f', 'segment', '-segment_time', String(sess.segmentSec), '-segment_format', 'mpegts',
        '-segment_start_number', String(startNumber), '-reset_timestamps', '0',
        '-segment_list', 'pipe:1', '-segment_list_type', 'csv', '-segment_list_flags', '+live',
        path.join(sess.dir, 'seg_%06d.ts')
    ];
    return [...base, ...input, ...codec, ...out];
}

// ── تشغيل ffmpeg مرة واحدة (حتى ينتهي) ──
function runFfmpegOnce(sess) {
    return new Promise((resolve) => {
        const args = buildFfmpegArgs(sess, sess.nextSeq);
        const proc = spawn('ffmpeg', args, { stdio: ['ignore', 'pipe', 'pipe'] });
        sess.proc = proc;
        let buf = '';
        let produced = 0;

        proc.stdout.on('data', (d) => {
            buf += d.toString();
            let i;
            while ((i = buf.indexOf('\n')) >= 0) {
                const line = buf.slice(0, i).trim();
                buf = buf.slice(i + 1);
                if (!line) continue;
                const m = line.split(',');           // filename,start,end
                if (m.length < 3) continue;
                const file = path.join(sess.dir, path.basename(m[0]));
                const dur = Math.max(0.1, parseFloat(m[2]) - parseFloat(m[1]));
                produced++;
                onSegmentReady(sess, file, dur);
            }
        });
        proc.stderr.on('data', (d) => { sess.lastFfmpegErr = d.toString().slice(-300); });
        proc.on('error', (e) => { sess.lastFfmpegErr = e.message; });
        proc.on('close', () => { sess.proc = null; resolve(produced); });
    });
}

// ── حلقة المصدر: تعيد التشغيل عند سقوط المصدر ──
async function sourceLoop(sess) {
    let failures = 0;
    while (!sess.stopped) {
        const before = sess.nextSeq;
        const startedAt = Date.now();
        await runFfmpegOnce(sess);
        if (sess.stopped) break;

        const progressed = sess.nextSeq > before;
        failures = progressed ? 0 : failures + 1;
        if (failures > CFG.maxSourceRestarts) {
            await sendEvent(sess, 'err', { reason: 'source_dead', detail: sess.lastFfmpegErr || '' });
            return stopSession(sess.chatId, sess.id, 'source_dead', true);
        }
        sess.discNext = true; // أول جزء بعد إعادة التشغيل = انقطاع في الطابع الزمني
        const wait = Math.min(1000 * Math.pow(2, Math.min(failures, 3)), 8000);
        const ranMs = Date.now() - startedAt;
        if (failures === 1 || failures % 5 === 0) {
            await sendEvent(sess, 'warn', { reason: 'source_reconnect', tries: failures, ranMs });
        }
        await sleep(wait);
    }
}

// ── جزء جاهز → طابور الإرسال ──
function onSegmentReady(sess, file, dur) {
    if (sess.stopped) return;
    const n = sess.nextSeq++;
    let size = 0;
    try { size = fs.statSync(file).size; } catch (_) {}
    const seg = { n, file, dur, size, state: 'queued', attempts: 0, disc: sess.discNext ? 1 : 0 };
    sess.discNext = false;
    sess.segs.set(n, seg);
    sess.queue.push(n);
    sess.queue.sort((a, b) => a - b);

    const backlog = sess.queue.length + sess.inflight;
    if (backlog > CFG.slowQueueThreshold && Date.now() - sess.lastSlowWarn > CFG.slowWarnEveryMs) {
        sess.lastSlowWarn = Date.now();
        sendEvent(sess, 'warn', { reason: 'slow_upload', backlog });
    }
    pump(sess);
}

function nextBackoff(attempts) {
    return Math.min(CFG.retryBaseMs * Math.pow(2, attempts - 1), CFG.retryMaxMs);
}

function pump(sess) {
    while (!sess.stopped && sess.inflight < CFG.uploadConcurrency && sess.queue.length) {
        const n = sess.queue.shift();
        const seg = sess.segs.get(n);
        if (!seg || seg.state === 'sent') continue;
        sess.inflight++;
        seg.state = 'sending';
        sendSegment(sess, seg).finally(() => {
            sess.inflight--;
            pump(sess);
        });
    }
}

async function sendSegment(sess, seg) {
    const caption = `TV|${sess.id}|seg|${JSON.stringify({ n: seg.n, d: Number(seg.dur.toFixed(2)), disc: seg.disc })}`;
    try {
        if (!fs.existsSync(seg.file)) { seg.state = 'gone'; return; }
        await sess.sock.sendMessage(sess.chatId, {
            document: { url: seg.file },
            mimetype: 'video/mp2t',
            fileName: `tv_${sess.id}_${pad(seg.n)}.ts`,
            caption
        });
        seg.state = 'sent';
        seg.sentAt = Date.now();
        sess.sentCount++;
        prune(sess);
    } catch (e) {
        seg.attempts++;
        seg.lastErr = e.message;
        if (seg.attempts >= CFG.maxAttempts || sess.stopped) {
            seg.state = 'failed';
            return;
        }
        seg.state = 'retry';
        const wait = nextBackoff(seg.attempts);
        setTimeout(() => {
            if (sess.stopped) return;
            seg.state = 'queued';
            sess.queue.push(seg.n);
            sess.queue.sort((a, b) => a - b);
            pump(sess);
        }, wait);
    }
}

// ── الاحتفاظ بآخر أجزاء مُرسلة لإعادة الإرسال عند الحاجة ──
function prune(sess) {
    let total = 0;
    const sent = [...sess.segs.values()].filter(s => s.state === 'sent' && s.size && fs.existsSync(s.file))
        .sort((a, b) => a.n - b.n);
    for (const s of sent) total += s.size;
    while (sent.length > CFG.keepMinSegments && total > CFG.keepBytes) {
        const old = sent.shift();
        total -= old.size;
        try { fs.unlinkSync(old.file); } catch (_) {}
        old.state = 'pruned';
    }
}

// ── المراقب: يوقف الجلسة إذا انقطعت نبضات التطبيق أو تجاوزت الحد الأقصى ──
function startWatchdog(sess) {
    sess.watchdog = setInterval(() => {
        if (sess.stopped) return;
        const now = Date.now();
        if (now - sess.lastPing > (sess.opts.watchdogMs || CFG.watchdogMs)) {
            stopSession(sess.chatId, sess.id, 'watchdog', true);
        } else if (now - sess.startedAt > CFG.maxSessionMs) {
            stopSession(sess.chatId, sess.id, 'max_duration', true);
        }
    }, 5000);
}

// ============================================================
// الواجهة العامة
// ============================================================

async function startSession({ sock, chatId, sid, url, segmentSec, tmpRootDir, opts = {} }) {
    if (!isValidSid(sid)) throw new Error('معرّف الجلسة غير صالح');
    if (!opts.sourceArgs && !isValidUrl(url)) throw new Error('رابط غير صالح');

    // نفس المعرّف يعمل بالفعل (إعادة محاولة من التطبيق عند ضياع الرد): لا نعيد التشغيل من الصفر
    if (sessions.has(chatId) && sessions.get(chatId).id === sid) {
        const same = sessions.get(chatId);
        same.lastPing = Date.now();
        await sendEvent(same, 'start', { segSec: same.segmentSec, hbMs: 45000, again: 1 });
        return same;
    }

    // تبديل القناة: نوقف الجلسة القديمة أولاً (فوري)
    if (sessions.has(chatId)) {
        const old = sessions.get(chatId);
        await stopSession(chatId, old.id, 'replaced', true);
    }

    const seg = opts.rawSegSec || Math.min(Math.max(parseInt(segmentSec) || CFG.segmentSec, 10), 120);
    const dir = path.join(tmpRootDir, `tv_${sid}`);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });

    const sess = {
        id: sid, chatId, sock, url, dir, opts, segmentSec: seg,
        segs: new Map(), queue: [], inflight: 0, nextSeq: 1, discNext: false,
        stopped: false, proc: null, startedAt: Date.now(), lastPing: Date.now(),
        lastSlowWarn: 0, sentCount: 0, lastFfmpegErr: ''
    };
    sessions.set(chatId, sess);
    startWatchdog(sess);

    await sendEvent(sess, 'start', { segSec: seg, hbMs: 45000 });

    if (!opts.skipProbe) {
        const ok = await probeSource(url, CFG.probeTimeoutMs);
        if (sess.stopped) return sess;
        if (!ok) {
            await sendEvent(sess, 'src_fail', { reason: 'probe' });
            await stopSession(chatId, sid, 'src_fail', false);
            return sess;
        }
    }
    await sendEvent(sess, 'src_ok', {});
    sourceLoop(sess).catch(async (e) => {
        await sendEvent(sess, 'err', { reason: 'internal', detail: e.message });
        stopSession(chatId, sid, 'internal', false);
    });
    return sess;
}

async function stopSession(chatId, sid, reason = 'user', notify = true) {
    const sess = sessions.get(chatId);
    if (!sess || (sid && sid !== 'all' && sess.id !== sid)) return false;
    if (sess.stopped) return true;
    sess.stopped = true;
    sessions.delete(chatId);
    clearInterval(sess.watchdog);
    sess.queue.length = 0;

    if (sess.proc) {
        const p = sess.proc;
        try { p.kill('SIGTERM'); } catch (_) {}
        setTimeout(() => { try { p.kill('SIGKILL'); } catch (_) {} }, 1500);
    }
    if (notify) await sendEvent(sess, 'stopped', { reason });

    // تنظيف الملفات بعد انتهاء الرفعات الجارية (أقصى انتظار 60 ثانية)
    (async () => {
        const t0 = Date.now();
        while (sess.inflight > 0 && Date.now() - t0 < 60000) await sleep(500);
        try { fs.rmSync(sess.dir, { recursive: true, force: true }); } catch (_) {}
    })();
    return true;
}

function ping(chatId, sid) {
    const s = sessions.get(chatId);
    if (!s || s.id !== sid) return false;
    s.lastPing = Date.now();
    return true;
}

async function resend(chatId, sid, list) {
    const s = sessions.get(chatId);
    if (!s || s.id !== sid) return false;
    for (const n of list.slice(0, 20)) {
        const seg = s.segs.get(n);
        if (!seg || !fs.existsSync(seg.file)) { await sendEvent(s, 'gone', { n }); continue; }
        if (seg.state === 'sending' || seg.state === 'queued') continue;
        seg.state = 'queued';
        seg.attempts = 0;
        s.queue.unshift(n);
    }
    s.queue.sort((a, b) => a - b);
    pump(s);
    return true;
}

function status(chatId) {
    const s = sessions.get(chatId);
    if (!s) return null;
    const states = {};
    for (const g of s.segs.values()) states[g.state] = (states[g.state] || 0) + 1;
    return { sid: s.id, segments: s.nextSeq - 1, sent: s.sentCount, queue: s.queue.length,
        inflight: s.inflight, states, upMin: Math.round((Date.now() - s.startedAt) / 60000) };
}

async function stopAll() {
    for (const [chatId, s] of [...sessions]) await stopSession(chatId, s.id, 'shutdown', false);
}
process.on('exit', () => { for (const s of sessions.values()) { try { s.proc && s.proc.kill('SIGKILL'); } catch (_) {} } });

module.exports = { isAllowedSender, startSession, stopSession, ping, resend, status, stopAll, isValidSid, isValidUrl, CFG, _sessions: sessions };
