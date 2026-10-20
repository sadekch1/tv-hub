'use strict';
// ────────────────────────────────────────────────────────────────
// منطق جلسة التلفاز (مستقل عن Baileys، لذلك قابل للاختبار)
//   • يرسل .tvplay ويعيد المحاولة إن لم يردّ البوت
//   • نبضات .tvping كل 45 ثانية (البوت يتوقف وحده إن غابت)
//   • يكتشف الأجزاء الناقصة ويطلبها بـ .tvresend
//   • إيقاف مؤكد: يكرر .tvstop حتى يصل «stopped»
// البروتوكول: TV|<sid>|<event>|<json>
// ────────────────────────────────────────────────────────────────

const DEFAULTS = {
    hbMs: 45000,
    startAckMs: 25000,
    startRetries: 2,
    stopRetryMs: 4000,
    stopTries: 5,
    gapCheckMs: 5000,
    gapAgeMs: 25000,
    resendEveryMs: 30000,
    resendMax: 4,
    resendBatch: 8
};

function parseBotText(text) {
    const m = /^TV\|([a-z0-9]{4,12})\|([a-z_]+)\|(.*)$/s.exec(String(text || '').trim());
    if (!m) return null;
    let data = {};
    try { data = m[3] ? JSON.parse(m[3]) : {}; } catch (_) {}
    return { sid: m[1], ev: m[2], data };
}

function parseSegCaption(caption) {
    const p = parseBotText(caption);
    if (!p || p.ev !== 'seg') return null;
    return { sid: p.sid, n: Number(p.data.n) || 0, d: Number(p.data.d) || 30, disc: p.data.disc ? 1 : 0 };
}

function parseSegName(name) {
    const m = /^tv_([a-z0-9]{4,12})_(\d{6})\.ts$/.exec(String(name || ''));
    return m ? { sid: m[1], n: parseInt(m[2], 10) } : null;
}

function createTv({ sendText, emit, opts = {} }) {
    const C = Object.assign({}, DEFAULTS, opts);
    let cur = null;   // الجلسة الحالية

    const clearTimers = (s) => {
        clearInterval(s.hb); clearInterval(s.gap);
        clearTimeout(s.startTimer); clearTimeout(s.stopTimer);
    };
    const send = (t) => { try { return Promise.resolve(sendText(t)).catch(() => {}); } catch (_) { return Promise.resolve(); } };
    const setState = (s, state, extra = {}) => {
        s.state = state;
        emit({ t: 'tvstate', sid: s.sid, state, ...extra });
    };

    function finish(s, reason) {
        if (s.state === 'stopped') return;
        clearTimers(s);
        setState(s, 'stopped', { reason });
        if (cur === s) cur = null;
    }

    function armStartWatch(s) {
        clearTimeout(s.startTimer);
        s.startTimer = setTimeout(() => {
            if (cur !== s || s.state !== 'starting' || s.heard) return;
            if (s.startTries < C.startRetries) {
                s.startTries++;
                send(`.tvplay ${s.sid} ${s.url} ${s.seg}`);   // آمن: البوت يتجاهل التكرار بنفس المعرّف
                armStartWatch(s);
            } else {
                emit({ t: 'tv', sid: s.sid, ev: 'err', data: { reason: 'no_bot_reply' } });
                stop(s.sid, 'no_bot_reply');
            }
        }, C.startAckMs);
    }

    function gapTick(s) {
        if (cur !== s || s.state === 'stopping' || s.state === 'stopped') return;
        const now = Date.now();
        const missing = [];
        for (let n = 1; n < s.maxSeen; n++) {
            if (s.have.has(n) || s.gone.has(n) || s.pending.has(n)) continue;
            const laterAt = s.laterSince(n);
            if (!laterAt || now - laterAt < C.gapAgeMs) continue;
            const t = s.tried.get(n) || { c: 0, last: 0 };
            if (t.c >= C.resendMax || now - t.last < C.resendEveryMs) continue;
            missing.push(n);
        }
        if (!missing.length) return;
        const batch = missing.slice(0, C.resendBatch);
        for (const n of batch) {
            const t = s.tried.get(n) || { c: 0, last: 0 };
            s.tried.set(n, { c: t.c + 1, last: now });
        }
        send(`.tvresend ${s.sid} ${batch.join(',')}`);
        emit({ t: 'tvgap', sid: s.sid, missing: batch });
    }

    function start({ sid, url, seg }) {
        if (cur) { clearTimers(cur); cur.state = 'stopped'; cur = null; }   // التبديل: البوت يوقف القديمة بنفسه
        const s = {
            sid, url, seg: seg || 30, state: 'starting', heard: false, startTries: 0,
            have: new Set(), pending: new Set(), gone: new Set(), tried: new Map(),
            seenAt: new Map(), maxSeen: 0, stopTries: 0
        };
        // وقت أول ظهور لأي جزء أحدث من n (لا نعتبر الجزء ناقصاً قبل أن يمر وقت كافٍ على وصول ما بعده)
        s.laterSince = (n) => {
            let min = 0;
            for (const [k, ts] of s.seenAt) if (k > n && (!min || ts < min)) min = ts;
            return min;
        };
        cur = s;
        setState(s, 'starting');
        send(`.tvplay ${sid} ${url} ${s.seg}`);
        armStartWatch(s);
        s.hb = setInterval(() => { if (cur === s && s.state !== 'stopping') send(`.tvping ${sid}`); }, C.hbMs);
        s.gap = setInterval(() => gapTick(s), C.gapCheckMs);
    }

    function stop(sid, reason = 'user') {
        const s = cur;
        if (!s || (sid && s.sid !== sid) || s.state === 'stopping' || s.state === 'stopped') return;
        s.stopReason = reason;
        clearInterval(s.hb); clearInterval(s.gap); clearTimeout(s.startTimer);   // لا نبضات: يحميك المراقب إن ضاع الأمر
        setState(s, 'stopping');
        const attempt = () => {
            if (cur !== s || s.state !== 'stopping') return;
            if (s.stopTries >= C.stopTries) { finish(s, 'timeout'); return; }
            s.stopTries++;
            send(`.tvstop ${s.sid}`);
            s.stopTimer = setTimeout(attempt, C.stopRetryMs);
        };
        attempt();
    }

    /** نص قادم من البوت */
    function onText(text) {
        const p = parseBotText(text);
        if (!p || !cur || p.sid !== cur.sid) return false;
        const s = cur;
        s.heard = true;
        clearTimeout(s.startTimer);
        emit({ t: 'tv', sid: p.sid, ev: p.ev, data: p.data });
        switch (p.ev) {
            case 'start': if (s.state === 'starting') setState(s, 'running'); break;
            case 'src_ok': if (s.state === 'starting') setState(s, 'running'); break;
            case 'src_fail': finish(s, 'src_fail'); break;
            case 'stopped': finish(s, s.stopReason || p.data.reason || 'stopped'); break;
            case 'err': if (p.data.reason === 'source_dead') finish(s, 'source_dead'); break;
            case 'gone': if (p.data.n) { s.gone.add(Number(p.data.n)); emit({ t: 'tvskip', sid: s.sid, n: Number(p.data.n) }); } break;
            default: break;
        }
        return true;
    }

    /** وصلت رسالة جزء (قبل تحميلها) — لاكتشاف الفجوات */
    function onSegSeen({ sid, n }) {
        const s = cur;
        if (!s || s.sid !== sid) return false;
        s.heard = true;
        clearTimeout(s.startTimer);
        if (s.state === 'starting') setState(s, 'running');
        if (!s.seenAt.has(n)) s.seenAt.set(n, Date.now());
        s.pending.add(n);
        if (n > s.maxSeen) s.maxSeen = n;
        return true;
    }

    /** اكتمل تحميل جزء */
    function onSegDone({ sid, n, d, disc, path }) {
        const s = cur;
        if (!s || s.sid !== sid) return false;
        s.have.add(n); s.pending.delete(n);
        emit({ t: 'tvseg', sid, n, d, disc, path });
        return true;
    }

    /** فشل تحميل جزء نهائياً → اطلبه من جديد فوراً */
    function onSegFailed({ sid, n }) {
        const s = cur;
        if (!s || s.sid !== sid) return false;
        s.pending.delete(n);
        const t = s.tried.get(n) || { c: 0, last: 0 };
        if (t.c < C.resendMax) {
            s.tried.set(n, { c: t.c + 1, last: Date.now() });
            send(`.tvresend ${sid} ${n}`);
            emit({ t: 'tvgap', sid, missing: [n] });
        }
        return true;
    }

    const isCurrent = (sid) => !!cur && cur.sid === sid && cur.state !== 'stopped';
    const dispose = () => { if (cur) { clearTimers(cur); cur = null; } };

    return { start, stop, onText, onSegSeen, onSegDone, onSegFailed, isCurrent, dispose, _cur: () => cur };
}

module.exports = { createTv, parseBotText, parseSegCaption, parseSegName, DEFAULTS };
