const { createTv, parseBotText, parseSegCaption, parseSegName } = require('./tvsession.js');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ok = (c, m) => { console.log((c ? 'PASS' : 'FAIL') + ' - ' + m); if (!c) process.exitCode = 1; };
const mk = (opts) => { const sent = [], ev = []; const tv = createTv({ sendText: t => { sent.push(t); }, emit: e => ev.push(e), opts }); return { tv, sent, ev }; };
const T = { hbMs: 120, startAckMs: 150, startRetries: 2, stopRetryMs: 80, stopTries: 3, gapCheckMs: 30, gapAgeMs: 100, resendEveryMs: 200, resendMax: 3 };

(async () => {
  // parsing
  ok(parseBotText('TV|abc123|start|{"segSec":30}').ev === 'start', 'parse event text');
  ok(parseBotText('hello') === null, 'ignore non-protocol text');
  const c = parseSegCaption('TV|abc123|seg|{"n":7,"d":30.4,"disc":1}');
  ok(c && c.n === 7 && c.disc === 1, 'parse segment caption');
  ok(parseSegName('tv_abc123_000012.ts').n === 12 && parseSegName('video.mp4') === null, 'parse segment filename');

  // 1) بدء + نبضات + إيقاف مؤكد
  let { tv, sent, ev } = mk(T);
  tv.start({ sid: 'abc123', url: 'http://x/1.ts', seg: 30 });
  ok(sent[0] === '.tvplay abc123 http://x/1.ts 30', 'sends .tvplay');
  tv.onText('TV|abc123|start|{}');
  ok(ev.some(e => e.t === 'tvstate' && e.state === 'running'), 'state running after bot ack');
  await sleep(300);
  ok(sent.filter(s => s === '.tvping abc123').length >= 2, 'heartbeats sent');
  tv.stop('abc123');
  const stopsAt = sent.length;
  await sleep(200);
  ok(sent.filter(s => s === '.tvstop abc123').length >= 2, 'stop is repeated until acknowledged');
  const pings = sent.filter(s => s === '.tvping abc123').length;
  await sleep(300);
  ok(sent.filter(s => s === '.tvping abc123').length === pings, 'heartbeats stop once stopping');
  tv.onText('TV|abc123|stopped|{"reason":"user"}');
  ok(ev.some(e => e.t === 'tvstate' && e.state === 'stopped'), 'stopped after ack');
  const n1 = sent.length; await sleep(250);
  ok(sent.length === n1, 'no more sends after ack');

  // 2) إيقاف بلا تأكيد → ينتهي بمهلة
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'tmo001', url: 'http://x', seg: 30 }); tv.onText('TV|tmo001|start|{}');
  tv.stop('tmo001'); await sleep(450);
  ok(ev.some(e => e.state === 'stopped' && e.reason === 'timeout'), 'stop without ack ends by timeout (bot watchdog covers it)');
  ok(sent.filter(s => s.startsWith('.tvstop')).length === 3, 'exactly stopTries attempts');

  // 3) البوت لا يرد → إعادة المحاولة ثم فشل واضح
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'dead01', url: 'http://x', seg: 30 });
  await sleep(700);
  ok(sent.filter(s => s.startsWith('.tvplay')).length === 3, 'retries .tvplay twice when bot is silent');
  ok(ev.some(e => e.t === 'tv' && e.data && e.data.reason === 'no_bot_reply'), 'reports no_bot_reply');

  // 4) فجوات: 1,2,4 → يطلب 3 ثم يتوقف عند وصوله
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'gap001', url: 'http://x', seg: 30 }); tv.onText('TV|gap001|start|{}');
  for (const n of [1, 2, 4]) { tv.onSegSeen({ sid: 'gap001', n }); tv.onSegDone({ sid: 'gap001', n, d: 30, disc: 0, path: '/p' + n }); }
  await sleep(60);
  ok(!sent.some(s => s.startsWith('.tvresend')), 'does not panic immediately (waits gapAgeMs)');
  await sleep(150);
  ok(sent.includes('.tvresend gap001 3'), 'requests only the missing part (3)');
  tv.onSegSeen({ sid: 'gap001', n: 3 }); tv.onSegDone({ sid: 'gap001', n: 3, d: 30, disc: 0, path: '/p3' });
  const r = sent.filter(s => s.startsWith('.tvresend')).length; await sleep(500);
  ok(sent.filter(s => s.startsWith('.tvresend')).length === r, 'stops asking once part arrives');

  // 5) «gone» من البوت → لا إعادة طلب
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'gone01', url: 'http://x', seg: 30 }); tv.onText('TV|gone01|start|{}');
  for (const n of [1, 3]) tv.onSegSeen({ sid: 'gone01', n });
  tv.onText('TV|gone01|gone|{"n":2}');
  await sleep(300);
  ok(!sent.some(s => s.startsWith('.tvresend gone01 2')), 'no resend for part the bot says is gone');
  ok(ev.some(e => e.t === 'tvskip' && e.n === 2), 'UI is told to skip part 2');

  // 6) فشل تحميل نهائي → طلب فوري
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'fail01', url: 'http://x', seg: 30 }); tv.onText('TV|fail01|start|{}');
  tv.onSegSeen({ sid: 'fail01', n: 1 }); tv.onSegFailed({ sid: 'fail01', n: 1 });
  ok(sent.includes('.tvresend fail01 1'), 'failed download triggers immediate resend');

  // 7) أحداث جلسة قديمة تُتجاهل + مصدر ميت
  ({ tv, sent, ev } = mk(T));
  tv.start({ sid: 'new001', url: 'http://x', seg: 30 });
  ok(tv.onText('TV|old999|start|{}') === false && tv.onSegSeen({ sid: 'old999', n: 1 }) === false, 'stale session events ignored');
  tv.onText('TV|new001|src_fail|{"reason":"probe"}');
  ok(ev.some(e => e.state === 'stopped' && e.reason === 'src_fail'), 'dead source ends the session immediately');
  tv.dispose();
  process.exit(process.exitCode || 0);
})();
