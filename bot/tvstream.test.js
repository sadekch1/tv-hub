const tv = require('./tvstream.js');
const fs = require('fs'); const path = require('path'); const { execSync } = require('child_process');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ROOT = require('os').tmpdir() + '/tvtest'; fs.rmSync(ROOT,{recursive:true,force:true}); fs.mkdirSync(ROOT,{recursive:true});
const SRC = ['-re','-f','lavfi','-i','testsrc2=size=320x180:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100'];
const ENC = ['-c:v','libx264','-preset','ultrafast','-g','25','-c:a','aac','-b:a','64k'];

function mkSock(failSeq) {
  const log = { files: [], texts: [] }; const fails = {};
  return { log, async sendMessage(chat, msg) {
    if (msg.text) { log.texts.push(msg.text); return; }
    const n = parseInt(msg.fileName.match(/_(\d{6})\.ts/)[1]);
    if (failSeq && n === failSeq && (fails[n] = (fails[n]||0)+1) <= 2) throw new Error('simulated send failure');
    await sleep(200); // upload time
    const copy = path.join(ROOT, 'recv_'+msg.fileName); fs.copyFileSync(msg.document.url, copy);
    log.files.push({ n, name: msg.fileName, caption: msg.caption, t: Date.now(), copy });
  }};
}
const ok = (c, m) => console.log((c ? 'PASS' : 'FAIL') + ' - ' + m);

(async () => {
  // ===== Test 1: تشغيل عادي + فشل إرسال + إيقاف =====
  const sock = mkSock(2);
  await tv.startSession({ sock, chatId: 'c1', sid: 'abc123', url: 'x', tmpRootDir: ROOT,
    opts: { sourceArgs: SRC, codecArgs: ENC, skipProbe: true, rawSegSec: 3 } });
  const hb = setInterval(() => tv.ping('c1','abc123'), 2000);
  await sleep(15000);
  console.log('status:', JSON.stringify(tv.status('c1')));
  clearInterval(hb);
  const t0 = Date.now();
  await tv.stopSession('c1','abc123','user',true);
  const sessDir = path.join(ROOT,'tv_abc123');
  await sleep(2500);
  const nums = sock.log.files.map(f=>f.n).sort((a,b)=>a-b);
  console.log('received seqs:', nums.join(','));
  ok(nums.length >= 3, 'received at least 3 segments');
  ok(nums.every((n,i)=>n===i+1), 'sequence contiguous with no gaps (seq 2 recovered after 2 failures)');
  ok(sock.log.texts.some(t=>t.includes('|stopped|')), 'stopped ack sent');
  ok(!fs.existsSync(sessDir), 'session dir cleaned after stop');
  ok(tv._sessions.size===0, 'no active session left');
  const ps = execSync("pgrep -x ffmpeg || true").toString().trim();
  ok(ps==='', 'ffmpeg process killed');
  console.log('caption sample:', sock.log.files[0].caption);
  // concat → one continuous stream?
  const list = sock.log.files.sort((a,b)=>a.n-b.n).map(f=>f.copy);
  execSync(`cat ${list.join(' ')} > ${ROOT}/all.ts`);
  const dur = parseFloat(execSync(`ffprobe -v error -show_entries format=duration -of csv=p=0 ${ROOT}/all.ts`).toString());
  const sum = sock.log.files.reduce((a,f)=>a+JSON.parse(f.caption.split('|')[3]).d,0);
  console.log(`concat duration=${dur.toFixed(1)}s  sum(captions)=${sum.toFixed(1)}s`);
  ok(Math.abs(dur-sum) < 1.5, 'plain concatenation of parts plays as one continuous stream (duration matches)');

  // ===== Test 2: watchdog (بدون نبضات) =====
  const sock2 = mkSock();
  await tv.startSession({ sock: sock2, chatId: 'c2', sid: 'wd0001', url: 'x', tmpRootDir: ROOT,
    opts: { sourceArgs: SRC, codecArgs: ENC, skipProbe: true, rawSegSec: 3, watchdogMs: 6000 } });
  await sleep(12500);
  ok(tv._sessions.size===0, 'watchdog stopped session after missing heartbeats');
  ok(sock2.log.texts.some(t=>t.includes('"reason":"watchdog"')), 'watchdog reason reported');

  // ===== Test 3: سقوط المصدر وإعادة الاتصال =====
  const sock3 = mkSock();
  const DROP = ['-re','-f','lavfi','-t','5','-i','testsrc2=size=320x180:rate=25'];
  await tv.startSession({ sock: sock3, chatId: 'c3', sid: 'drop01', url: 'x', tmpRootDir: ROOT,
    opts: { sourceArgs: DROP, codecArgs: ['-c:v','libx264','-preset','ultrafast','-g','25'], skipProbe: true, rawSegSec: 3 } });
  const hb3 = setInterval(() => tv.ping('c3','drop01'), 2000);
  await sleep(17000); clearInterval(hb3);
  const n3 = sock3.log.files.map(f=>f.n).sort((a,b)=>a-b);
  console.log('after source drops, seqs:', n3.join(','), '| disc flags:', sock3.log.files.sort((a,b)=>a.n-b.n).map(f=>JSON.parse(f.caption.split('|')[3]).disc).join(''));
  ok(n3.length>=4 && n3.every((n,i)=>n===i+1), 'numbering continues across source restarts');
  ok(sock3.log.files.some(f=>JSON.parse(f.caption.split('|')[3]).disc===1), 'discontinuity flag set after restart');
  await tv.stopSession('c3','drop01','user',false);

  // ===== Test 4: مصدر ميت =====
  const sock4 = mkSock();
  await tv.startSession({ sock: sock4, chatId: 'c4', sid: 'dead01', url: 'http://127.0.0.1:9/none.ts', tmpRootDir: ROOT, opts: {} });
  await sleep(1500);
  ok(sock4.log.texts.some(t=>t.includes('|src_fail|')), 'dead source reported immediately (src_fail) instead of waiting minutes');
  ok(tv._sessions.size===0, 'dead-source session closed');
  process.exit(0);
})();
