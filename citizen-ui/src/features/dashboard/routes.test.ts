import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import express, { type ErrorRequestHandler } from 'express';
import session from 'express-session';
import nunjucks from 'nunjucks';
import path from 'node:path';
import request from 'supertest';
import { registerRoutes } from './routes';
import { createApp } from '../../app';
import { londonToday, type Claim } from '../respond/model';

const fixture = (reference = '000MC001', overrides: Partial<Claim> = {}): Claim => ({
  reference, claimantId: 'demo-claimant', state: 'AWAITING_RESPONSE',
  claimant: { name: 'Alex Morgan', address: { line1: '10 Market Street', city: 'Bristol', postcode: 'BS1 1AA' } },
  defendant: { name: 'Taylor James', address: { line1: '5 Park Lane', city: 'Bath', postcode: 'BA1 1AA' } },
  principalAmount: 100, interestAmount: 5, fee: 35, totalAmount: 140, reason: 'Unpaid invoice',
  createdAt: '2026-01-01T12:00:00Z', responseDeadline: '2099-01-01', eligibleForDefaultJudgment: false, securityCode: 'DEMO2026',
  ...overrides
});

// B6 owns sign-in in a parallel branch; inject only that identity boundary, using real route/client/templates.
function signedInApp(role: 'claimant' | 'defendant' | 'caseworker' = 'claimant') {
  const app = express();
  app.use(express.urlencoded({ extended: true }));
  app.use(session({ secret: 'route-tests-only', resave: false, saveUninitialized: false }));
  const env = nunjucks.configure([
    path.join(__dirname, '../../views'), path.join(__dirname, 'views'),
    path.join(__dirname, '../respond/views'), path.join(process.cwd(), 'node_modules/govuk-frontend/dist')
  ], { express: app, autoescape: true });
  env.addFilter('money', value => new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' }).format(Number(value || 0)));
  env.addFilter('date', value => value ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'long', timeZone: 'Europe/London' }).format(new Date(value)) : 'Not provided');
  env.addFilter('statusLabel', value => (value || '').toLowerCase().replaceAll('_', ' '));
  app.use((req, res, next) => {
    req.session.user = { id: role === 'claimant' ? 'demo-claimant' : 'demo-defendant', role, name: 'Demo User' };
    req.requestId = 'dashboard-test';
    Object.assign(res.locals, { user: req.session.user, csrfToken: 'test-token', demoBanner: 'DEMO – this is not a real government service' });
    next();
  });
  registerRoutes(app);
  const errors: ErrorRequestHandler = (_error, req, res, _next) => res.status(500).render('errors/service.njk', { requestId: req.requestId });
  app.use(errors);
  return app;
}

describe('claimant dashboard routes', () => {
  let claims: Claim[];
  let submitted: string[];
  let requests: { url: URL; init: RequestInit }[];
  let listStatus: number;
  let submitStatus: number;

  beforeEach(() => {
    claims = [fixture(), fixture('000LC001', { createdAt: '2026-02-01T12:00:00Z', responseDeadline: '2020-01-01', eligibleForDefaultJudgment: true })];
    submitted = []; requests = []; listStatus = 200; submitStatus = 200;
    vi.stubGlobal('fetch', vi.fn(async (input: string, init: RequestInit) => {
      const url = new URL(input); requests.push({ url, init });
      if (url.pathname === '/api/claims') {
        if (listStatus !== 200) return Response.json({ message: 'Downstream unavailable' }, { status: listStatus });
        const items = claims.filter(item => item.reference.includes(url.port === '8081' ? 'LC' : 'MC'));
        return Response.json({ items, total: items.length, page: 0, size: 250 });
      }
      const reference = url.pathname.split('/')[3];
      const claim = claims.find(item => item.reference === reference);
      if (!claim) return Response.json({ message: 'Not found' }, { status: 404 });
      if (url.pathname.endsWith('/events')) return Response.json([{ id: 'event-1', type: 'CLAIM_ISSUED', description: 'Claim issued to defendant', createdAt: '2026-01-01T12:00:00Z' }]);
      if (url.pathname.endsWith('/default-judgment')) {
        if (submitStatus !== 200) return Response.json({ message: 'Claim changed' }, { status: submitStatus });
        submitted.push(reference);
        Object.assign(claim, { state: 'AWAITING_DEFAULT_JUDGMENT', judgmentRequestedAt: '2026-11-06T12:00:00Z', eligibleForDefaultJudgment: false });
      }
      return Response.json(claim);
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('requires claimant sign-in and CSRF protection through the shared app', async () => {
    const app = createApp();
    expect((await request(app).get('/dashboard')).headers.location).toBe('/sign-in');
    expect((await request(app).post('/dashboard/000LC001/default-judgment').send({ statementOfTruth: 'true' })).status).toBe(403);
    expect((await request(signedInApp('defendant')).get('/dashboard')).status).toBe(403);
    expect(submitted).toHaveLength(0);
  });

  it('merges modern and legacy claims, with status tags and scoped headers', async () => {
    const page = await request(signedInApp()).get('/dashboard');
    expect(page.status).toBe(200);
    expect(page.text).toContain('000MC001');
    expect(page.text).toContain('000LC001');
    expect(page.text.indexOf('000LC001')).toBeLessThan(page.text.indexOf('000MC001'));
    expect(page.text).toContain('govuk-tag');
    expect(page.text).toContain('/dashboard/000LC001/default-judgment');
    expect(page.text).not.toContain('/dashboard/000MC001/default-judgment');
    expect(page.text).toContain('Showing 1 to 2 of 2 claims');
    expect(requests).toHaveLength(2);
    for (const item of requests) {
      expect(item.url.searchParams.get('claimantId')).toBe('demo-claimant');
      expect((item.init.headers as Headers).get('X-Demo-Role')).toBe('claimant');
      expect((item.init.headers as Headers).get('X-Demo-User-Id')).toBe('demo-claimant');
      expect((item.init.headers as Headers).get('X-Request-ID')).toBe('dashboard-test');
    }
  });

  it('renders a helpful empty state and does not disguise API failure as no claims', async () => {
    claims = [];
    expect((await request(signedInApp()).get('/dashboard')).text).toContain('You have no claims yet');
    listStatus = 500;
    const response = await request(signedInApp()).get('/dashboard');
    expect(response.status).toBe(500);
    expect(response.text).toContain('Sorry, there is a problem with the service.');
    expect(response.text).not.toContain('You have no claims yet');
  });

  it('paginates the merged list without duplicate or missing records', async () => {
    claims = Array.from({ length: 12 }, (_, i) => fixture(`000${i % 2 ? 'LC' : 'MC'}${String(i + 1).padStart(3, '0')}`, { createdAt: `2026-02-${String(i + 1).padStart(2, '0')}T12:00:00Z` }));
    const app = signedInApp();
    const first = await request(app).get('/dashboard');
    expect(first.text).toContain('Showing 1 to 10 of 12 claims');
    expect(first.text).toContain('/dashboard?page=1');
    const second = await request(app).get('/dashboard?page=1');
    expect(second.status).toBe(200);
    expect(second.text).toContain('Showing 11 to 12 of 12 claims');
    expect(second.text).toContain('000MC001');
    expect(second.text).not.toContain('000LC012');
    expect((await request(app).get('/dashboard?page=-1')).headers.location).toBe('/dashboard');
    expect((await request(app).get('/dashboard?page=9999')).headers.location).toBe('/dashboard');
    expect((await request(app).get('/dashboard?page=9')).headers.location).toBe('/dashboard');
  });

  it.each(['000MC001', '000LC001'])('uses the owning API for detail and event history on %s', async reference => {
    const response = await request(signedInApp()).get(`/dashboard/${reference}`);
    expect(response.status).toBe(200);
    expect(response.text).toContain('Your claim against Taylor James');
    expect(response.text).toContain('Claim issued to defendant');
    expect(response.text).toContain('id="demo-security-code">DEMO2026');
    expect(requests).toHaveLength(2);
    expect(requests.every(item => item.url.port === (reference.includes('LC') ? '8081' : '8080'))).toBe(true);
  });

  it('shows responses and escapes party names and defence text', async () => {
    claims[0].state = 'RESPONDED';
    claims[0].defendant.name = '<img src=x onerror=alert(1)>';
    claims[0].response = { type: 'ADMIT_PART', admittedAmount: 25, reason: '<script>danger</script>', paymentOption: 'BY_DATE', paymentDate: '2099-03-01', statementOfTruth: true };
    const response = await request(signedInApp()).get('/dashboard/000MC001');
    expect(response.status).toBe(200);
    expect(response.text).toContain('£25.00');
    expect(response.text).toContain('1 March 2099');
    expect(response.text).toContain('&lt;script&gt;');
    expect(response.text).not.toContain('<img src=x');
    expect(response.text).not.toContain('href="/dashboard/000MC001/default-judgment"');
  });

  it('rejects unknown or another claimant’s references before loading history or mutating', async () => {
    const app = signedInApp();
    expect((await request(app).get('/dashboard/not-a-reference')).status).toBe(404);
    expect((await request(app).get('/dashboard/000MC999')).status).toBe(404);
    claims[1].claimantId = 'another-claimant';
    expect((await request(app).get('/dashboard/000LC001')).status).toBe(404);
    expect((await request(app).post('/dashboard/000LC001/default-judgment').type('form').send({ statementOfTruth: 'true' })).status).toBe(404);
    expect(submitted).toHaveLength(0);
    expect(requests.some(item => item.url.pathname.endsWith('/events'))).toBe(false);
  });

  it.each(['000MC001', '000LC001'])('confirms and submits default judgment exactly once for %s', async reference => {
    const claim = claims.find(item => item.reference === reference)!;
    claim.responseDeadline = '2020-01-01'; claim.eligibleForDefaultJudgment = true;
    const app = signedInApp();
    const route = `/dashboard/${reference}/default-judgment`;
    expect((await request(app).get(route)).text).toContain('Submit request');
    const missing = await request(app).post(route).type('form').send({});
    expect(missing.status).toBe(400);
    expect(missing.text).toContain('href="#statementOfTruth"');
    const submittedResponse = await request(app).post(route).type('form').send({ statementOfTruth: 'true' });
    expect(submittedResponse.headers.location).toBe(`/dashboard/${reference}/judgment-confirmation`);
    const downstream = requests.find(item => item.url.pathname.endsWith('/default-judgment'))!;
    expect(JSON.parse(downstream.init.body as string)).toEqual({ statementOfTruth: true });
    expect(downstream.url.port).toBe(reference.includes('LC') ? '8081' : '8080');
    expect((await request(app).get(submittedResponse.headers.location)).text).toContain('Judgment request submitted');
    expect((await request(app).post(route).type('form').send({ statementOfTruth: 'true' })).status).toBe(409);
    expect(submitted).toEqual([reference]);
  });

  it.each([
    { responseDeadline: londonToday() }, { responseDeadline: '2099-01-01' }, { eligibleForDefaultJudgment: false },
    { response: { type: 'REJECT', statementOfTruth: true } }, { judgmentRequestedAt: '2026-01-01T12:00:00Z' }, { state: 'SETTLED' }
  ])('hides and blocks requests when ineligible: %j', override => {
    return (async () => {
      Object.assign(claims[1], override);
      const app = signedInApp();
      expect((await request(app).get('/dashboard')).text).not.toContain('href="/dashboard/000LC001/default-judgment"');
      const direct = await request(app).get('/dashboard/000LC001/default-judgment');
      expect(direct.text).toContain('You cannot request judgment by default');
      expect(direct.text).not.toContain('Submit request');
      expect((await request(app).post('/dashboard/000LC001/default-judgment').type('form').send({ statementOfTruth: 'true' })).status).toBe(409);
      expect(submitted).toHaveLength(0);
    })();
  });

  it('handles races and does not display a false confirmation', async () => {
    const app = signedInApp();
    expect((await request(app).get('/dashboard/000MC001/judgment-confirmation')).headers.location).toBe('/dashboard/000MC001');
    submitStatus = 409;
    const response = await request(app).post('/dashboard/000LC001/default-judgment').type('form').send({ statementOfTruth: 'true' });
    expect(response.status).toBe(409);
    expect(response.text).toContain('The claim has changed');
    expect(submitted).toHaveLength(0);
  });
});
