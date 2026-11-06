import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import request from 'supertest';
import { createApp } from '../../app';
import type { Claim, DefendantResponse } from './model';

const fixture = (reference = '000MC001'): Claim => ({
  reference, claimantId: 'demo-claimant', state: 'AWAITING_RESPONSE',
  claimant: { name: 'Alex Morgan', address: { line1: '10 Market Street', city: 'Bristol', postcode: 'BS1 1AA' } },
  defendant: { name: 'Taylor James', address: { line1: '5 Park Lane', city: 'Bath', postcode: 'BA1 1AA' } },
  principalAmount: 100, interestAmount: 5, fee: 35, totalAmount: 140, reason: 'Unpaid invoice',
  amountBreakdown: [{ reason: 'Repair work', amount: 100 }], timeline: [{ date: '2026-01-01', description: 'Invoice sent' }],
  createdAt: '2026-01-01T12:00:00Z', issuedAt: '2026-01-02T12:00:00Z', responseDeadline: '2099-01-01',
  eligibleForDefaultJudgment: false, securityCode: 'DEMO2026'
});

describe('respond routes through the shared application', () => {
  let claim: Claim;
  let submissions: DefendantResponse[];
  let requests: { url: string; init: RequestInit }[];
  let lookupStatus: number;
  let submitStatus: number;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    claim = fixture(); submissions = []; requests = []; lookupStatus = 200; submitStatus = 200;
    // Contract-backed HTTP responses exercise real clients, route loading, sessions, CSRF and Nunjucks.
    fetchMock = vi.fn(async (input: string, init: RequestInit) => {
      const url = new URL(input);
      requests.push({ url: input, init });
      if (url.pathname === '/api/claims/lookup') {
        if (lookupStatus !== 200) return Response.json({ message: 'Lookup failed' }, { status: lookupStatus });
        return Response.json(claim);
      }
      if (url.pathname.endsWith('/response')) {
        if (submitStatus !== 200) return Response.json({ message: 'Response rejected' }, { status: submitStatus });
        const response = JSON.parse(init.body as string);
        submissions.push(response);
        claim = { ...claim, state: 'RESPONDED', response: { ...response, submittedAt: '2026-11-06T12:00:00Z' } };
        return Response.json(claim);
      }
      return Response.json(claim);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  async function start(reference = '000MC001') {
    claim.reference = reference;
    const agent = request.agent(createApp());
    const page = await agent.get('/respond');
    expect(page.status).toBe(200);
    const token = page.text.match(/name="csrf-token" content="([^"]+)"/)![1];
    const post = (path: string, data: Record<string, string> = {}) => agent.post(path).type('form').send({ _csrf: token, ...data });
    const lookup = await post('/respond', { reference: reference.toLowerCase(), securityCode: 'demo2026' });
    expect(lookup.headers.location).toBe('/respond/claim');
    return { agent, post, token };
  }

  it('normalizes reference/code and propagates the request ID without revealing the code', async () => {
    const { agent } = await start();
    const lookup = requests[0];
    expect(JSON.parse(lookup.init.body as string)).toEqual({ reference: '000MC001', securityCode: 'DEMO2026' });
    expect((lookup.init.headers as Headers).get('X-Request-ID')).toBeTruthy();
    const page = await agent.get('/respond/claim');
    expect(page.status).toBe(200);
    expect(page.text).toContain('Unpaid invoice');
    expect(page.text).toContain('£140.00');
    expect(page.text).not.toContain('DEMO2026');
    expect(page.text).toContain('Respond to this claim');
  });

  it('validates lookup fields with accessible summaries and keeps entered values', async () => {
    const agent = request.agent(createApp());
    const page = await agent.get('/respond');
    const token = page.text.match(/name="csrf-token" content="([^"]+)"/)![1];
    const invalid = await agent.post('/respond').type('form').send({ _csrf: token, reference: 'oops', securityCode: 'x' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('href="#reference"');
    expect(invalid.text).toContain('href="#securityCode"');
    expect(invalid.text).toContain('value="OOPS"');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([403, 404])('shows the same safe lookup error for API %s', async status => {
    const { agent, post } = await start();
    lookupStatus = status;
    const response = await post('/respond', { reference: '000MC002', securityCode: 'WRONG123' });
    expect(response.status).toBe(400);
    expect(response.text).toContain('We could not find a claim with that reference and security code');
    expect((await agent.get('/respond/claim')).headers.location).toBe('/respond');
  });

  it('does not allow a direct response submission without lookup or a CSRF token', async () => {
    const agent = request.agent(createApp());
    expect((await agent.get('/respond/check-answers')).headers.location).toBe('/respond');
    expect((await agent.post('/respond/check-answers').send({ statementOfTruth: 'true' })).status).toBe(403);
    expect(submissions).toHaveLength(0);
  });

  it.each(['000MC001', '000LC001'])('submits a full admission to the correct service for %s', async reference => {
    const { agent, post } = await start(reference);
    expect((await agent.get('/respond/response')).text).toContain('How do you respond to the claim?');
    expect((await post('/respond/response', { type: 'ADMIT_ALL' })).headers.location).toBe('/respond/payment');
    expect((await agent.get('/respond/payment')).status).toBe(200);
    expect((await post('/respond/payment', { paymentOption: 'IMMEDIATELY' })).headers.location).toBe('/respond/check-answers');
    const check = await agent.get('/respond/check-answers');
    expect(check.status).toBe(200);
    expect(check.text).toContain('href="/respond/response"');
    expect(check.text).toContain('href="/respond/payment"');
    const noTruth = await post('/respond/check-answers');
    expect(noTruth.status).toBe(400);
    expect(noTruth.text).toContain('href="#statementOfTruth"');
    expect(submissions).toHaveLength(0);
    const submitted = await post('/respond/check-answers', { statementOfTruth: 'true' });
    expect(submitted.headers.location).toBe('/respond/confirmation');
    expect(submissions).toEqual([{ type: 'ADMIT_ALL', admittedAmount: 140, paymentOption: 'IMMEDIATELY', statementOfTruth: true }]);
    expect(requests.every(item => new URL(item.url).port === (reference.includes('LC') ? '8081' : '8080'))).toBe(true);
    const confirmation = await agent.get('/respond/confirmation');
    expect(confirmation.status).toBe(200);
    expect(confirmation.text).toContain('Response submitted');
    expect(confirmation.text).toContain(reference);
    expect((await post('/respond/check-answers', { statementOfTruth: 'true' })).headers.location).toBe('/respond/confirmation');
    expect(submissions).toHaveLength(1);
  });

  it('submits a partial admission with penny-accurate monthly instalments', async () => {
    const { agent, post } = await start();
    await post('/respond/response', { type: 'ADMIT_PART' });
    expect((await agent.get('/respond/details')).status).toBe(200);
    const invalid = await post('/respond/details', { admittedAmount: '140', reason: 'Some work was not completed' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('Some work was not completed');
    await post('/respond/details', { admittedAmount: '55.25', reason: 'Some work was not completed' });
    const paymentError = await post('/respond/payment', { paymentOption: 'INSTALMENTS', instalmentAmount: '60' });
    expect(paymentError.status).toBe(400);
    expect(paymentError.text).toContain('href="#instalmentAmount"');
    await post('/respond/payment', { paymentOption: 'INSTALMENTS', instalmentAmount: '11.05' });
    expect((await agent.get('/respond/check-answers')).text).toContain('£11.05 each month');
    await post('/respond/check-answers', { statementOfTruth: 'true' });
    expect(submissions[0]).toEqual({ type: 'ADMIT_PART', admittedAmount: 55.25, reason: 'Some work was not completed', paymentOption: 'INSTALMENTS', instalmentAmount: 11.05, statementOfTruth: true });
  });

  it('submits a defence without stale payment fields and escapes the explanation', async () => {
    const { agent, post } = await start();
    await post('/respond/response', { type: 'ADMIT_ALL' });
    await post('/respond/payment', { paymentOption: 'IMMEDIATELY' });
    await post('/respond/response', { type: 'REJECT' });
    expect((await agent.get('/respond/check-answers')).headers.location).toBe('/respond/details');
    expect((await post('/respond/details', { reason: '' })).status).toBe(400);
    await post('/respond/details', { reason: '<script>alert(1)</script> I have already paid.' });
    const check = await agent.get('/respond/check-answers');
    expect(check.text).toContain('&lt;script&gt;');
    expect(check.text).not.toContain('<script>alert');
    expect(check.text).not.toContain('Payment proposal');
    await post('/respond/check-answers', { statementOfTruth: 'true' });
    expect(submissions[0]).toEqual({ type: 'REJECT', reason: '<script>alert(1)</script> I have already paid.', statementOfTruth: true });
  });

  it('validates real dates and sends ISO payment dates only when selected', async () => {
    const { agent, post } = await start();
    await post('/respond/response', { type: 'ADMIT_ALL' });
    const invalid = await post('/respond/payment', { paymentOption: 'BY_DATE', paymentDay: '31', paymentMonth: '2', paymentYear: '2099' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('href="#paymentDay"');
    await post('/respond/payment', { paymentOption: 'BY_DATE', paymentDay: '3', paymentMonth: '4', paymentYear: '2099' });
    expect((await agent.get('/respond/check-answers')).text).toContain('3 April 2099');
    await post('/respond/check-answers', { statementOfTruth: 'true' });
    expect(submissions[0].paymentDate).toBe('2099-04-03');
    expect(submissions[0].instalmentAmount).toBeUndefined();
  });

  it('prevents skipping questions or submitting without a selected response', async () => {
    const { agent, post } = await start();
    expect((await agent.get('/respond/check-answers')).headers.location).toBe('/respond/response');
    expect((await post('/respond/response', { type: '__proto__' })).status).toBe(400);
    await post('/respond/response', { type: 'ADMIT_PART' });
    expect((await agent.get('/respond/payment')).headers.location).toBe('/respond/details');
    expect((await post('/respond/check-answers', { statementOfTruth: 'true' })).headers.location).toBe('/respond/details');
    expect(submissions).toHaveLength(0);
  });

  it('allows a late defence before, but not after, a judgment request', async () => {
    const { agent, post } = await start();
    claim.state = 'AWAITING_DEFAULT_JUDGMENT';
    claim.responseDeadline = '2020-01-01';
    expect((await agent.get('/respond/response')).status).toBe(200);
    await post('/respond/response', { type: 'REJECT' });
    await post('/respond/details', { reason: 'Already paid' });
    claim.judgmentRequestedAt = '2026-01-01T12:00:00Z';
    expect((await post('/respond/check-answers', { statementOfTruth: 'true' })).status).toBe(409);
    expect(submissions).toHaveLength(0);
  });

  it('does not turn an upstream failure into success or lose saved answers', async () => {
    const { agent, post } = await start();
    await post('/respond/response', { type: 'REJECT' });
    await post('/respond/details', { reason: 'Already paid' });
    submitStatus = 500;
    const failed = await post('/respond/check-answers', { statementOfTruth: 'true' });
    expect(failed.status).toBe(500);
    expect(failed.text).toContain('Sorry, there is a problem with the service.');
    expect(failed.text).toContain(failed.headers['x-request-id']);
    expect((await agent.get('/respond/check-answers')).text).toContain('Already paid');
    expect((await agent.get('/respond/confirmation')).headers.location).toBe('/respond/claim');
  });
});
