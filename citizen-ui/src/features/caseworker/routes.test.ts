import { beforeAll, beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import { createApp } from '../../app';

const documentId = '11111111-1111-4111-8111-111111111111';
const modern = {
  id: '11111111-1111-4111-8111-111111111112', reference: '000MC001', state: 'AWAITING_RESPONSE',
  claimant: { type: 'INDIVIDUAL', name: 'Priya Shah', email: 'priya@example.test', address: { line1: '12 Market Road', city: 'Bristol', postcode: 'BS1 1AA' } },
  defendant: { type: 'BUSINESS', name: 'Severn Repairs Ltd', address: { line1: '8 Demo Street', city: 'Bath', postcode: 'BA1 1AA' } },
  principalAmount: 1200, interestAmount: 24, fee: 80, totalAmount: 1304, dailyInterest: 0.263014,
  interest: { claimed: true, rate: 8, startDate: '2026-01-01' },
  amountBreakdown: [{ reason: 'Unpaid invoice', amount: 1200 }], reason: 'The repair invoice remains unpaid.',
  timeline: [{ date: '2026-01-01', description: 'Invoice sent' }],
  createdAt: '2026-11-05T09:00:00Z', issuedAt: '2026-11-05T09:30:00Z', serviceDate: '2026-11-10', responseDeadline: '2026-11-24', acknowledged: false,
  securityCode: 'DEMO2026', eligibleForDefaultJudgment: false
};
const legacy = { ...modern, reference: '000LC001', state: 'RESPONDED', createdAt: '2026-11-04T09:00:00Z', claimant: { ...modern.claimant, name: 'Oliver Jones' },
  response: { type: 'ADMIT_PART', admittedAmount: 600, reason: 'Some work was incomplete.', paymentOption: 'BY_DATE', paymentDate: '2026-12-01', submittedAt: '2026-11-05T12:00:00Z' } };
type Call = { url: URL; method: string; headers: Headers; body: any };
let app: Express;
let agent: ReturnType<typeof request.agent>;
let calls: Call[];
let claims: (typeof modern | typeof legacy)[];
let notes: { id: string; text: string; author: string; createdAt: string }[];
let failure: { path: string; status: number; legacyOnly?: boolean } | undefined;
let documents: any[];
let metricsErrors: any[];

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const csrf = (html: string): string => html.match(/name="csrf-token" content="([^"]+)"/)![1];

// Exercise the real Express routes, sessions, CSRF and API clients with deterministic HTTP contract responses.
async function apiResponse(input: string | URL | Request, init?: RequestInit): Promise<Response> {
  const url = new URL(String(input));
  const method = init?.method || 'GET';
  const headers = new Headers(init?.headers);
  const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
  calls.push({ url, method, headers, body });
  const isLegacy = url.port === '8081';
  if (failure && url.pathname === failure.path && (!failure.legacyOnly || isLegacy)) return json({ status: failure.status, code: 'TEST_ERROR', message: 'Upstream failure' }, failure.status);
  if (url.pathname === '/api/claims') {
    let items = claims.filter(claim => claim.reference.startsWith(isLegacy ? '000LC' : '000MC'));
    if (url.searchParams.has('state')) items = items.filter(claim => claim.state === url.searchParams.get('state'));
    if (url.searchParams.has('from')) items = items.filter(claim => claim.createdAt.slice(0, 10) >= url.searchParams.get('from')!);
    if (url.searchParams.has('to')) items = items.filter(claim => claim.createdAt.slice(0, 10) <= url.searchParams.get('to')!);
    return json({ items, total: items.length, page: 0, size: Number(url.searchParams.get('size')) });
  }
  if (url.pathname === '/api/ops/metrics') return json({ service: isLegacy ? 'legacy-claim-store' : 'civil-case-api', windowSeconds: 60, requestCount: 120, errorCount: 3, requestRate: 2, errorRate: 0.025, latestErrors: isLegacy ? [] : metricsErrors });
  if (url.pathname === '/api/ops/reset') return json({ status: 'reset', claims: isLegacy ? 40 : 160 });
  if (url.pathname.endsWith(`/documents/${documentId}`)) return new Response('%PDF-1.4 synthetic evidence', { headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="invoice.pdf"' } });
  if (url.pathname.endsWith('/documents')) return json(documents);
  if (url.pathname.endsWith('/events')) return json([
    { id: 'event-1', type: 'CLAIM_CREATED', description: 'Claim created', createdAt: '2026-11-05T09:00:00Z', actor: 'demo-claimant' },
    { id: 'event-2', type: 'CLAIM_ISSUED', description: 'Claim issued', createdAt: '2026-11-05T09:30:00Z', actor: 'demo-claimant' }
  ]);
  if (url.pathname.endsWith('/notes')) {
    if (method === 'POST') { notes.push({ id: 'new-note', ...body, createdAt: '2026-11-06T12:00:00Z' }); return json(notes.at(-1), 201); }
    return json(notes);
  }
  const claim = claims.find(item => url.pathname === `/api/claims/${item.reference}`);
  return claim ? json(claim) : json({ message: 'Not found' }, 404);
}

async function signIn(role = 'caseworker', returnTo?: string): Promise<request.Response> {
  const page = await agent.get(`/sign-in${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ''}`);
  return agent.post('/sign-in').type('form').send({ _csrf: csrf(page.text), role });
}

beforeAll(() => { app = createApp(); });
beforeEach(() => {
  calls = []; claims = structuredClone([modern, legacy]); notes = []; failure = undefined;
  documents = [{ id: documentId, fileName: 'invoice.pdf', contentType: 'application/pdf', sizeBytes: 2048, scanStatus: 'CLEAN', createdAt: modern.createdAt }];
  metricsErrors = [{ timestamp: '2026-11-06T12:00:00Z', requestId: 'error-ref-123', method: 'POST', path: '/api/claims', message: 'Example API error', status: 500 }];
  agent = request.agent(app);
  vi.stubGlobal('fetch', vi.fn(apiResponse));
  vi.stubEnv('DEMO_RESET_ENABLED', 'true');
  vi.stubEnv('DEMO_RESET_TOKEN', 'test-reset-token-not-for-browser');
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('fake sign-in', () => {
  it('renders all roles with the required demo branding and no government logos', async () => {
    const res = await agent.get('/sign-in');
    expect(res.status).toBe(200);
    for (const text of ['HMCTS demo', 'this is not a real government service', 'Claimant', 'Defendant', 'Caseworker', 'No password']) expect(res.text).toContain(text);
    expect(res.text).not.toMatch(/govuk-header__logotype|govuk-footer__copyright-logo/);
  });
  it.each([['claimant', '/dashboard'], ['defendant', '/respond'], ['caseworker', '/caseworker']])('signs in as %s and rotates the session', async (role, location) => {
    const before = await agent.get('/sign-in');
    const after = await agent.post('/sign-in').type('form').send({ role, _csrf: csrf(before.text) });
    expect(after.status).toBe(303); expect(after.headers.location).toBe(location);
    expect(after.headers['set-cookie'][0]).not.toBe(before.headers['set-cookie'][0]);
  });
  it.each(['', 'administrator', '__proto__'])('rejects invalid role %s', async role => {
    const res = await signIn(role);
    expect(res.status).toBe(400); expect(res.text).toContain('Select how you want to use the demo');
    expect(res.text).toContain('href="#role"');
  });
  it('requires CSRF for sign-in and sign-out', async () => {
    expect((await agent.post('/sign-in').send({ role: 'caseworker' })).status).toBe(403);
    await signIn();
    expect((await agent.post('/sign-out')).status).toBe(403);
  });
  it('validates a missing sign-in body instead of returning a server error', async () => {
    const page = await agent.get('/sign-in');
    expect((await agent.post('/sign-in').set('X-CSRF-Token', csrf(page.text))).status).toBe(400);
  });
  it('returns staff to the protected page after sign-in', async () => {
    const protectedPage = await agent.get('/caseworker/000LC001/history');
    expect(protectedPage.status).toBe(303); expect(protectedPage.headers.location).toBe('/sign-in');
    expect((await signIn()).headers.location).toBe('/caseworker/000LC001/history');
  });
  it.each(['https://evil.test', '//evil.test', '/caseworker/../../evil', '/caseworker/%2e%2e/sign-out', '/ops\\evil', '/respond'])('rejects unsafe or wrong-role return destination %s', async returnTo => {
    expect((await signIn('caseworker', returnTo)).headers.location).toBe('/caseworker');
  });
  it('supports a safe citizen return destination', async () => {
    expect((await signIn('claimant', '/make-claim/task-list')).headers.location).toBe('/make-claim/task-list');
  });
  it('destroys the signed-in session on sign-out', async () => {
    await signIn();
    const page = await agent.get('/caseworker');
    const res = await agent.post('/sign-out').type('form').send({ _csrf: csrf(page.text) });
    expect(res.status).toBe(303); expect(res.headers.location).toBe('/sign-in');
    expect((await agent.get('/caseworker')).headers.location).toBe('/sign-in');
  });
});

describe('caseworker case management', () => {
  it.each(['/caseworker', '/caseworker/000MC001', '/ops', '/ops/reset'])('requires the staff role for %s', async path => {
    expect((await agent.get(path)).headers.location).toBe('/sign-in');
    await signIn('claimant');
    const res = await agent.get(path);
    expect(res.status).toBe(403); expect(res.text).toContain('Caseworker access required');
    expect(calls).toHaveLength(0);
  });
  it('merges modern and legacy lists and propagates request and staff identity headers', async () => {
    await signIn();
    const res = await agent.get('/caseworker').set('X-Request-ID', 'staff-list-123');
    expect(res.status).toBe(200); expect(res.headers['cache-control']).toBe('no-store');
    expect(res.text).toContain('000MC001'); expect(res.text).toContain('000LC001');
    expect(res.text.indexOf('000MC001')).toBeLessThan(res.text.indexOf('000LC001'));
    expect(res.text).toContain('Priya Shah'); expect(res.text).toContain('Oliver Jones');
    expect(calls).toHaveLength(2);
    for (const call of calls) {
      expect(call.headers.get('X-Request-ID')).toBe('staff-list-123');
      expect(call.headers.get('X-Demo-User-Id')).toBe('demo-caseworker');
      expect(call.headers.get('X-Demo-Role')).toBe('caseworker');
    }
  });
  it('passes state and date filters to both stores and preserves selected fields', async () => {
    await signIn();
    const res = await agent.get('/caseworker?state=RESPONDED&from=2026-11-01&to=2026-11-04');
    expect(res.status).toBe(200); expect(res.text).toContain('000LC001'); expect(res.text).not.toContain('000MC001');
    expect(res.text).toMatch(/value="RESPONDED" selected/); expect(res.text).toContain('value="2026-11-01"');
    for (const call of calls) expect(Object.fromEntries(call.url.searchParams)).toMatchObject({ state: 'RESPONDED', from: '2026-11-01', to: '2026-11-04', page: '0', size: '250' });
  });
  it.each(['state=INVALID', 'from=2026-02-30', 'to=not-a-date', 'from=2026-11-06&to=2026-11-01', 'page=-1', 'page=999999'])('rejects invalid filters without calling APIs: %s', async query => {
    await signIn();
    const res = await agent.get(`/caseworker?${query}`);
    expect(res.status).toBe(400); expect(res.text).toContain('There is a problem'); expect(calls).toHaveLength(0);
  });
  it('paginates the combined result and keeps filters in next/previous links', async () => {
    claims = Array.from({ length: 45 }, (_, index) => ({ ...modern, reference: `000MC${String(index + 1).padStart(3, '0')}` }));
    await signIn();
    const res = await agent.get('/caseworker?state=AWAITING_RESPONSE&page=1');
    expect(res.status).toBe(200); expect(res.text).toContain('Showing 21 to 40 of 45');
    expect(res.text).toContain('href="/caseworker?state=AWAITING_RESPONSE&amp;page=2"');
    expect(res.text).toContain('href="/caseworker?state=AWAITING_RESPONSE"');
  });
  it('renders empty filtered results without claiming an upstream failure is an empty list', async () => {
    await signIn();
    expect((await agent.get('/caseworker?state=SETTLED')).text).toContain('No cases found');
    failure = { path: '/api/claims', status: 500, legacyOnly: true };
    const res = await agent.get('/caseworker').set('X-Request-ID', 'list-failure');
    expect(res.status).toBe(500); expect(res.text).toContain('Sorry, there is a problem with the service.');
    expect(res.text).toContain('list-failure'); expect(res.text).not.toContain('No cases found');
  });
  it.each(['summary', 'parties', 'claim', 'documents', 'history'])('renders the %s tab with accessible server-side navigation', async tab => {
    await signIn();
    const res = await agent.get(`/caseworker/000MC001/${tab}`);
    expect(res.status).toBe(200); expect(res.text).toContain('Case 000MC001');
    expect(res.text).toContain('aria-current="page"');
    for (const name of ['Summary', 'Parties', 'Claim', 'Documents', 'History']) expect(res.text).toContain(`>${name}</a>`);
    expect(res.text).not.toContain('DEMO2026');
  });
  it('shows party details, itemised amounts and defendant response', async () => {
    await signIn();
    const parties = await agent.get('/caseworker/000MC001/parties');
    expect(parties.text).toContain('12 Market Road'); expect(parties.text).toContain('priya@example.test');
    const claim = await agent.get('/caseworker/000LC001/claim');
    for (const content of ['£1,304.00', 'Unpaid invoice', 'Invoice sent', 'Admits part of the claim', '£600.00', 'Some work was incomplete.']) expect(claim.text).toContain(content);
    expect(calls.at(-1)?.url.port).toBe('8081');
  });
  it('normalises lowercase references and routes legacy detail calls only to legacy', async () => {
    await signIn();
    expect((await agent.get('/caseworker/000lc001/history')).status).toBe(200);
    expect(calls.every(call => call.url.port === '8081' && call.url.pathname.startsWith('/api/claims/000LC001'))).toBe(true);
  });
  it.each(['/caseworker/not-a-reference', '/caseworker/000MC999', '/caseworker/000MC001/unknown'])('returns a branded 404 for %s', async path => {
    await signIn(); const res = await agent.get(path);
    expect(res.status).toBe(404); expect(res.text).toContain('Page not found');
  });
  it('lists and downloads clean evidence through the UI without leaking API addresses', async () => {
    await signIn();
    const list = await agent.get('/caseworker/000LC001/documents');
    expect(list.text).toContain('invoice.pdf'); expect(list.text).toContain('2.0 KB');
    expect(list.text).toContain(`/caseworker/000LC001/documents/${documentId}`);
    const file = await agent.get(`/caseworker/000LC001/documents/${documentId}`).set('X-Request-ID', 'download-123');
    expect(file.status).toBe(200); expect(file.headers['content-type']).toContain('application/pdf');
    expect(file.headers['content-disposition']).toContain('attachment');
    expect(Buffer.from(file.body).toString()).toContain('%PDF-1.4');
    expect(calls.at(-1)?.url.port).toBe('8081'); expect(calls.at(-1)?.headers.get('X-Request-ID')).toBe('download-123');
  });
  it('shows empty document and history states, and orders events newest first', async () => {
    await signIn(); documents = [];
    expect((await agent.get('/caseworker/000MC001/documents')).text).toContain('No documents have been added');
    const history = await agent.get('/caseworker/000MC001/history');
    expect(history.text).toContain('No case notes have been added');
    expect(history.text.indexOf('Claim issued')).toBeLessThan(history.text.indexOf('Claim created'));
  });
  it('validates and saves a note to the correct store using the session author', async () => {
    await signIn();
    const history = await agent.get('/caseworker/000LC001/history');
    const bad = await agent.post('/caseworker/000LC001/notes').type('form').send({ _csrf: csrf(history.text), text: '   ' });
    expect(bad.status).toBe(400); expect(bad.text).toContain('Enter a case note');
    const good = await agent.post('/caseworker/000LC001/notes').type('form').send({ _csrf: csrf(bad.text), text: '  Reviewed the invoice.  ', author: 'forged-author' });
    expect(good.status).toBe(303); expect(good.headers.location).toBe('/caseworker/000LC001/history');
    const saved = calls.find(call => call.method === 'POST');
    expect(saved?.url.port).toBe('8081'); expect(saved?.body).toEqual({ text: 'Reviewed the invoice.', author: 'demo-caseworker' });
    const result = await agent.get(good.headers.location);
    expect(result.text).toContain('Case note added.'); expect(result.text).toContain('Reviewed the invoice.');
    expect((await agent.get(good.headers.location)).text).not.toContain('Case note added.');
  });
  it('rejects overlong notes, retains text and blocks notes without CSRF', async () => {
    await signIn();
    const history = await agent.get('/caseworker/000MC001/history');
    const res = await agent.post('/caseworker/000MC001/notes').type('form').send({ _csrf: csrf(history.text), text: 'x'.repeat(10001) });
    expect(res.status).toBe(400); expect(res.text).toContain('10,000 characters or fewer'); expect(res.text).toContain('x'.repeat(10001));
    expect((await agent.post('/caseworker/000MC001/notes').send({ text: 'Missing CSRF' })).status).toBe(403);
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0);
  });
  it('does not claim a note was saved when its API rejects the request', async () => {
    await signIn(); const history = await agent.get('/caseworker/000MC001/history');
    failure = { path: '/api/claims/000MC001/notes', status: 500 };
    const res = await agent.post('/caseworker/000MC001/notes').set('X-Request-ID', 'note-failed-123').type('form').send({ _csrf: csrf(history.text), text: 'A note' });
    expect(res.status).toBe(500); expect(res.text).toContain('note-failed-123'); expect(notes).toHaveLength(0);
    expect(res.text).not.toContain('Case note added.');
  });
  it('validates missing note and reset bodies with valid header CSRF tokens', async () => {
    await signIn(); const history = await agent.get('/caseworker/000MC001/history');
    expect((await agent.post('/caseworker/000MC001/notes').set('X-CSRF-Token', csrf(history.text))).status).toBe(400);
    expect((await agent.post('/ops/reset').set('X-CSRF-Token', csrf(history.text))).status).toBe(400);
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0);
  });
  it('renders incomplete draft parties and amounts without requiring issued-case fields', async () => {
    claims[0] = { ...modern, state: 'DRAFT', claimant: {} as typeof modern.claimant, defendant: {} as typeof modern.defendant,
      interest: undefined as any, amountBreakdown: [], reason: '', timeline: [], issuedAt: undefined as any, responseDeadline: undefined as any, serviceDate: undefined as any };
    await signIn();
    for (const tab of ['summary', 'parties', 'claim']) {
      const res = await agent.get(`/caseworker/000MC001/${tab}`);
      expect(res.status).toBe(200); expect(res.text).toContain('Claimant not provided');
    }
  });
  it('escapes stored party names, case notes and document filenames', async () => {
    await signIn();
    claims[0].claimant.name = '<script>alert(1)</script>';
    notes.push({ id: 'n1', text: '<img src=x onerror=alert(1)>', author: '<b>staff</b>', createdAt: modern.createdAt });
    documents[0].fileName = '<script>file</script>.pdf';
    for (const tab of ['history', 'documents']) {
      const res = await agent.get(`/caseworker/000MC001/${tab}`);
      expect(res.status).toBe(200); expect(res.text).not.toContain('<script>alert(1)</script>'); expect(res.text).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
      expect(res.text).not.toContain('<img src=x'); expect(res.text).not.toContain('<script>file');
    }
  });
});

describe('service operations and reset', () => {
  it('shows real API rates, counts and recent error request references', async () => {
    await signIn(); const res = await agent.get('/ops');
    expect(res.status).toBe(200);
    for (const content of ['civil-case-api', 'legacy-claim-store', '2.00', '2.5', '120 requests', '3 errors', 'error-ref-123', 'Example API error', 'Reset demo data']) expect(res.text).toContain(content);
    expect(res.text).not.toContain('test-reset-token-not-for-browser');
  });
  it('renders no-error windows and escapes upstream messages', async () => {
    await signIn(); metricsErrors = [];
    expect((await agent.get('/ops')).text).toContain('No errors reported');
    metricsErrors = [{ timestamp: modern.createdAt, requestId: '<script>x</script>', message: '<script>error</script>' }];
    const res = await agent.get('/ops');
    expect(res.text).not.toContain('<script>error'); expect(res.text).toContain('&lt;script&gt;error&lt;/script&gt;');
  });
  it('does not disguise upstream metrics failures as zero errors', async () => {
    await signIn(); failure = { path: '/api/ops/metrics', status: 500 };
    const res = await agent.get('/ops').set('X-Request-ID', 'metrics-failed-123');
    expect(res.status).toBe(500); expect(res.text).toContain('metrics-failed-123'); expect(res.text).not.toContain('No errors reported');
  });
  it('requires confirmation and CSRF before resetting either store', async () => {
    await signIn(); const page = await agent.get('/ops/reset');
    expect(page.status).toBe(200); expect(page.text).toContain('This cannot be undone');
    expect((await agent.post('/ops/reset').type('form').send({ _csrf: csrf(page.text) })).status).toBe(400);
    expect((await agent.post('/ops/reset').type('form').send({ confirm: 'yes' })).status).toBe(403);
    expect(calls).toHaveLength(0);
  });
  it.each(['disabled', 'missing-token'])('refuses reset when %s', async condition => {
    await signIn();
    if (condition === 'disabled') vi.stubEnv('DEMO_RESET_ENABLED', 'false'); else vi.stubEnv('DEMO_RESET_TOKEN', '');
    const page = await agent.get('/ops/reset'); expect(page.status).toBe(403);
    const res = await agent.post('/ops/reset').type('form').send({ _csrf: csrf(page.text), confirm: 'yes' });
    expect(res.status).toBe(403); expect(res.text).toContain('Demo reset is disabled'); expect(calls).toHaveLength(0);
  });
  it('resets both stores with a server-only token and reports actual restored count once', async () => {
    await signIn(); const page = await agent.get('/ops/reset');
    const res = await agent.post('/ops/reset').set('X-Request-ID', 'reset-123').type('form').send({ _csrf: csrf(page.text), confirm: 'yes' });
    expect(res.status).toBe(303); expect(res.headers.location).toBe('/ops');
    expect(calls).toHaveLength(2);
    for (const call of calls) {
      expect(call.method).toBe('POST'); expect(call.url.pathname).toBe('/api/ops/reset'); expect(call.body).toEqual({});
      expect(call.headers.get('X-Demo-Reset-Token')).toBe('test-reset-token-not-for-browser'); expect(call.headers.get('X-Request-ID')).toBe('reset-123');
    }
    const confirmation = await agent.get('/ops');
    expect(confirmation.text).toContain('200 claims restored'); expect(confirmation.text).not.toContain('test-reset-token-not-for-browser');
    expect((await agent.get('/ops')).text).not.toContain('200 claims restored');
  });
  it('reports partial reset failure without claiming success or hiding the reset already performed', async () => {
    await signIn(); const page = await agent.get('/ops/reset');
    failure = { path: '/api/ops/reset', status: 500, legacyOnly: true };
    const res = await agent.post('/ops/reset').set('X-Request-ID', 'partial-reset-123').type('form').send({ _csrf: csrf(page.text), confirm: 'yes' });
    expect(res.status).toBe(502); expect(res.text).toContain('Demo reset did not complete');
    expect(res.text).toContain('160 claims restored'); expect(res.text).toContain('Reset could not be confirmed');
    expect(res.text).toContain('partial-reset-123'); expect(res.text).not.toContain('test-reset-token-not-for-browser');
    expect(calls).toHaveLength(2);
  });
});
