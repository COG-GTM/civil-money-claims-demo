// Usage (from citizen-ui, after npm run build): node scripts/bench-cold-start.mjs . 20
// Starts a fresh UI process per run, waits for /health like the deploy smoke, then times the first GET of the hub.
import { spawn } from 'node:child_process';
const [dir, runsArg] = process.argv.slice(2); const runs = Number(runsArg || 20);
const base = 'http://127.0.0.1:3199/f2ec3d90';
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function time(url) { const t = performance.now(); const r = await fetch(url, { headers: { 'user-agent': 'deploy-smoke' } }); await r.text(); return [performance.now() - t, r.status]; }
const first = [], second = [];
for (let i = 0; i < runs; i++) {
  const p = spawn(process.execPath, ['--require', './dist/observability/tracing.js', 'dist/server.js'], { cwd: dir,
    env: { ...process.env, PORT: '3199', BASE_PATH: '/f2ec3d90', NODE_ENV: 'production', SESSION_SECRET: 'x'.repeat(32), DD_TRACE_ENABLED: process.env.DD_TRACE_ENABLED || 'false' }, stdio: 'ignore' });
  for (;;) { try { if ((await fetch(base + '/health')).ok) break; } catch {} await sleep(25); }
  const [a, s1] = await time(base + '/'); const [b] = await time(base + '/');
  if (s1 !== 200) throw new Error('status ' + s1);
  first.push(a); second.push(b); p.kill(); await new Promise(r => p.on('exit', r));
}
const pct = (xs, q) => { const s = [...xs].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)].toFixed(1); };
for (const [n, xs] of [['first GET /', first], ['second GET /', second]])
  console.log(`${n}: n=${xs.length} p50=${pct(xs, .5)} p95=${pct(xs, .95)} p99=${pct(xs, .99)} max=${Math.max(...xs).toFixed(1)} ms`);
