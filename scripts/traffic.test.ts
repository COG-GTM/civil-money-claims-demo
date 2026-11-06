import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { BrowserSession, configuration, csrfToken, makeClaim, respond, run } from './traffic.ts';

test('configuration rejects invalid rates and token-bearing base URLs', () => {
  assert.equal(configuration({}).rate, 6);
  for (const rate of ['0', '-1', 'NaN', '1000']) assert.throws(() => configuration({ TRAFFIC_RATE_PER_MINUTE: rate }));
  assert.throws(() => configuration({ TRAFFIC_BASE_URL: 'https://user:secret@example.test' }));
  assert.throws(() => configuration({ TRAFFIC_BASE_URL: 'https://example.test/?token=secret' }));
  assert.throws(() => configuration({ TRAFFIC_CONCURRENCY: '1.5' }));
});
test('CSRF token parsing supports meta/hidden attributes in either order', () => {
  assert.equal(csrfToken('<meta content="abc" name="csrf-token">'), 'abc');
  assert.equal(csrfToken('<input name="_csrf" value="abc&amp;123">'), 'abc&123');
  assert.throws(() => csrfToken('<html></html>'));
});

test('HTTP journeys preserve cookies/CSRF, upload real files and exercise every response type', async () => {
  const posts: { path: string; body: string; type: string }[] = [];
  let failures: string[] = [];
  const server = createServer(async (req, res) => {
    const path = req.url || '/';
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const body = Buffer.concat(chunks).toString('latin1');
    const type = String(req.headers['content-type'] || '');
    if (req.method === 'POST') {
      if (!req.headers.cookie?.includes('cmc.sid=test-cookie')) failures.push('Missing cookie');
      if (!req.headers['x-request-id']?.toString().startsWith('traffic-')) failures.push('Missing correlation');
      if (type.includes('multipart')) {
        if (req.headers['x-csrf-token'] !== 'csrf123') failures.push('Missing multipart CSRF');
        if (!body.includes('%PDF-') && !body.includes('PNG')) failures.push('Missing evidence bytes');
      } else if (new URLSearchParams(body).get('_csrf') !== 'csrf123') failures.push('Missing CSRF');
      posts.push({ path, body, type });
      if (path.endsWith('/upload')) { res.setHeader('Content-Type', 'application/json'); return res.end('{"redirect":"/make-claim/evidence"}'); }
      const location = path === '/make-claim/payment' ? '/make-claim/confirmation' : path === '/respond/check-answers' ? '/respond/confirmation' : path;
      res.writeHead(303, { Location: location }); return res.end();
    }
    res.setHeader('Set-Cookie', 'cmc.sid=test-cookie; HttpOnly; Path=/');
    res.setHeader('Content-Type', 'text/html');
    res.end('<meta name="csrf-token" content="csrf123"><div data-security-code="ABCD1234">000MC161</div>');
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    for (let i = 0; i < 3; i++) {
      const claim = await makeClaim(base, i);
      assert.deepEqual(claim, { reference: '000MC161', securityCode: 'ABCD1234' });
      await respond(base, claim, i);
    }
    assert.deepEqual(failures, []);
    assert.equal(posts.filter(p => p.type.includes('multipart')).length, 3);
    assert.equal(posts.filter(p => p.path === '/respond/details').length, 2);
    assert.equal(posts.filter(p => p.path === '/respond/payment').length, 2);
    assert.equal(posts.filter(p => p.path === '/respond/check-answers').length, 3);
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
});

test('HTTP errors and cross-origin redirects are not silently successful', async () => {
  const server = createServer((req, res) => {
    if (req.url === '/redirect') res.writeHead(302, { Location: 'https://outside.example.test/' });
    else res.writeHead(500, { 'X-Request-ID': 'failure123' });
    res.end('private body must not appear in errors');
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = new BrowserSession(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
  try {
    await assert.rejects(browser.request('/'), /HTTP 500; request_id=failure123/);
    await assert.rejects(browser.request('/redirect'), /cross-origin/);
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
});

test('a short duration does not wait for a long rate interval', async () => {
  let requests = 0;
  const server = createServer((_req, res) => { requests++; res.writeHead(503); res.end(); });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const start = performance.now();
    const status = await run({ baseUrl: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, rate: 0.1,
      concurrency: 1, iterations: 0, durationSeconds: 0.02 }, AbortSignal.timeout(1000));
    assert.equal(status, 1);
    assert.equal(requests, 1);
    assert.ok(performance.now() - start < 900, 'Duration should bound scheduling, not the 600-second interval');
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
});
