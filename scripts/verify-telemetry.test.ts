import { test } from 'node:test';
import assert from 'node:assert/strict';
import { configuration, verify } from './verify-telemetry.ts';

const env = { TELEMETRY_BASE_URL: 'https://demo.example.test/f2ec3d90', DD_VERSION: 'a'.repeat(40), DD_API_KEY: 'test-key', DD_APP_KEY: 'test-app-key' };

test('configuration retains prefixes, supports separate Compose APIs and generates fresh IDs', () => {
  const first = configuration(env);
  assert.deepEqual(first.targets.map(target => target.url), [
    'https://demo.example.test/f2ec3d90/',
    'https://demo.example.test/f2ec3d90/api/claims?page=0&size=1',
    'https://demo.example.test/f2ec3d90/legacy/api/claims?page=0&size=1'
  ]);
  assert.notEqual(first.targets[0].requestId, configuration(env).targets[0].requestId);
  const local = configuration({ ...env, TELEMETRY_CIVIL_API_URL: 'http://localhost:8080', TELEMETRY_LEGACY_API_URL: 'http://localhost:8081' });
  assert.equal(local.targets[2].url, 'http://localhost:8081/api/claims?page=0&size=1');
});

test('configuration rejects missing secrets, query injection, untrusted sites and credential-bearing targets', () => {
  for (const overrides of [
    { DD_API_KEY: '' }, { DD_APP_KEY: '' }, { DD_VERSION: 'local' }, { DD_ENV: 'demo OR env:prod' },
    { DD_SITE: 'attacker.test' }, { TELEMETRY_BASE_URL: 'https://user:secret@demo.example.test' },
    { TELEMETRY_BASE_URL: 'https://demo.example.test?key=secret' }, { TELEMETRY_TIMEOUT_SECONDS: 'Infinity' }
  ]) assert.throws(() => configuration({ ...env, ...overrides }));
});

test('acceptance requires target-specific logs AND server spans for all three services', async () => {
  const config = configuration(env);
  let searches = 0;
  const output: string[] = [];
  const fetcher: typeof fetch = async (input, init) => {
    if (init?.method !== 'POST') {
      assert.equal((init?.headers as Record<string, string>)['DD-API-KEY'], undefined);
      return new Response('', { headers: { 'x-request-id': (init?.headers as Record<string, string>)['X-Request-ID'] } });
    }
    searches++;
    const body = JSON.parse(String(init.body));
    const kind = String(input).includes('/spans/') ? 'spans' : 'logs';
    const query = (body.data?.attributes || body).filter.query as string;
    const target = config.targets.find(target => query.includes(`@request_id:${target.requestId}`));
    assert.ok(target);
    assert.ok(query.includes(`service:${target.service} env:demo version:${env.DD_VERSION}`));
    if (kind === 'spans') assert.ok(query.includes('@span.kind:server'));
    return Response.json({ data: [{ id: 'evidence', attributes: { trace_id: '123', span_id: '456' } }] });
  };
  await verify(config, fetcher, async () => {}, line => output.push(line));
  assert.equal(searches, 6);
  assert.equal(output.filter(line => JSON.parse(line).event === 'verified').length, 3);
  assert.ok(output.every(line => !line.includes('test-key') && !line.includes('test-app-key')));
});

test('HTTP success without logs or spans is NOT a pass', async () => {
  const config = { ...configuration(env), timeoutMs: 0 };
  for (const missing of ['logs', 'spans']) {
    const fetcher: typeof fetch = async (input, init) => init?.method === 'POST'
      ? Response.json({ data: String(input).includes(`/${missing}/`) ? [] : [{ id: 'other' }] })
      : new Response('', { headers: { 'x-request-id': (init?.headers as Record<string, string>)['X-Request-ID'] } });
    await assert.rejects(verify(config, fetcher, async () => {}, () => {}), /Telemetry NOT verified/);
  }
});

test('unpreserved IDs, failed probes and unauthorized searches fail closed without secret output', async () => {
  const config = configuration(env);
  await assert.rejects(verify(config, async () => new Response(''), async () => {}, () => {}), /request ID was not preserved/);
  await assert.rejects(verify(config, async () => new Response('', { status: 500 }), async () => {}, () => {}), /probe HTTP 500/);
  const fetcher: typeof fetch = async (_input, init) => init?.method === 'POST'
    ? new Response('test-key must never be printed', { status: 403 })
    : new Response('', { headers: { 'x-request-id': (init?.headers as Record<string, string>)['X-Request-ID'] } });
  await assert.rejects(verify(config, fetcher, async () => {}, () => {}), error =>
    error instanceof Error && error.message === 'Datadog logs search HTTP 403');
});
