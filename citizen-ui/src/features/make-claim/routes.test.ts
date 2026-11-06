import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import request from 'supertest';
import { createApp } from '../../app';
import { validateAmounts, readDate } from './model';

const reference = '000MC161';
const quote = { principalAmount: 1200, interestAmount: 24.46, dailyInterest: 0.263014, fee: 80, totalAmount: 1304.46, calculationDate: '2026-11-06' };
const party = { type: 'INDIVIDUAL', name: 'Alex Morgan', email: 'alex@example.test', line1: '14 Example Lane', line2: '', city: 'Bristol', postcode: 'BS1 4AA', country: 'England' };
const payment = { cardNumber: '4242 4242 4242 4242', cardholderName: 'Demo Citizen', expiryMonth: '12', expiryYear: '2099', securityCode: '123' };
const pdf = Buffer.from('%PDF-1.4\n1 0 obj <</Type /Catalog>> endobj\n%%EOF');
let agent: ReturnType<typeof request.agent>, csrf: string;
let calls: { path: string; method: string; body: any; headers: Headers }[];
let documents: any[], claim: Record<string, unknown>, failUpload: boolean;

function post(path: string, body: Record<string, unknown> = {}) {
  return agent.post(`/make-claim/${path}`).type('form').send({ _csrf: csrf, ...body });
}
async function eligibility() {
  for (const [path, field] of [['eligibility', 'over18'], ['eligibility/location', 'englandWales'], ['eligibility/amount', 'underLimit'], ['eligibility/parties', 'oneDefendant']]) {
    const result = await post(path, { [field]: 'yes' });
    expect(result.status, result.text).toBe(302);
  }
}
async function answers() {
  await eligibility();
  for (const [path, body] of [
    ['your-details', party], ['defendant-details', { ...party, type: 'BUSINESS', name: 'Example Repairs Ltd', email: '' }],
    ['amount', { 'items[0][reason]': 'Unpaid invoice', 'items[0][amount]': '1200.00' }],
    ['interest', { claimed: 'yes', day: '1', month: '8', year: '2026' }],
    ['reason', { reason: 'The defendant did not pay for the agreed work.' }],
    ['timeline', { 'events[0][day]': '1', 'events[0][month]': '7', 'events[0][year]': '2026', 'events[0][description]': 'Work completed and invoice sent.' }]
  ] as const) {
    expect((await agent.get(`/make-claim/${path}`)).status).toBe(200);
    const result = await post(path, body);
    expect(result.status, result.text).toBe(302);
  }
  await post('evidence');
}

beforeEach(async () => {
  calls = []; documents = []; failUpload = false;
  claim = { id: '9a55fc51-3c06-4514-85e1-bdb35285b8e7', reference, state: 'DRAFT', ...quote };
  // Mock only the HTTP API boundary; Express, session, CSRF, Multer and templates are real.
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init: RequestInit = {}) => {
    const path = new URL(String(input)).pathname, method = init.method || 'GET';
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ path, method, body, headers: new Headers(init.headers) });
    const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
    if (path === '/api/fees/quote') return json(quote);
    if (path === '/api/claims' || (path === `/api/claims/${reference}` && method === 'PUT')) {
      claim = { ...claim, ...body };
      return json(claim, method === 'POST' ? 201 : 200);
    }
    if (path.endsWith('/documents') && method === 'GET') return json(documents);
    if (path.endsWith('/documents') && method === 'POST') {
      if (failUpload) return json({ message: 'Document store unavailable', requestId: 'upload-outage' }, 500);
      const file = (body as FormData).get('file') as File;
      const document = { id: 'eb298370-578b-4cf4-a970-c1dc86b52da9', fileName: file.name, sizeBytes: file.size, contentType: file.type, scanStatus: 'CLEAN' };
      documents.push(document); return json(document, 201);
    }
    if (path.includes('/documents/') && method === 'DELETE') { documents = []; return new Response(null, { status: 204 }); }
    if (path.endsWith('/issue')) return json({ ...claim, state: 'AWAITING_RESPONSE', securityCode: 'DEMO2026', serviceDate: '2026-11-11', responseDeadline: '2026-11-25' });
    throw new Error(`Unexpected API call: ${method} ${path}`);
  }));
  agent = request.agent(createApp());
  const response = await agent.get('/make-claim/eligibility');
  expect(response.status, response.text).toBe(200);
  csrf = response.text.match(/name="csrf-token" content="([^"]+)"/)![1];
});
afterEach(() => vi.unstubAllGlobals());

describe('make a claim journey', () => {
  it('guards skipped eligibility and displays linked validation errors', async () => {
    expect((await agent.get('/make-claim/payment')).headers.location).toBe('/make-claim/eligibility');
    expect((await agent.get('/make-claim/eligibility/parties')).headers.location).toBe('/make-claim/eligibility');
    const invalid = await post('eligibility');
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('href="#over18"');
    expect(invalid.text).toContain('Error: Are you 18 or over?');
    expect((await post('eligibility', { over18: 'no' })).headers.location).toBe('/make-claim/not-eligible');
    expect((await agent.get('/make-claim/not-eligible')).text).toContain('You cannot use this demo');
    expect(calls).toHaveLength(0);
  });

  it('completes all tasks, uploads evidence, quotes fees, reviews, pays and confirms', async () => {
    await answers();
    expect((await agent.get('/make-claim/evidence')).status).toBe(200);
    const upload = await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).attach('file', pdf, { filename: 'invoice.pdf', contentType: 'application/pdf' });
    expect(upload.status).toBe(200);
    expect(upload.body.redirect).toBe('/make-claim/evidence?uploaded=true');
    const evidence = await agent.get(upload.body.redirect);
    expect(evidence.status, evidence.text).toBe(200);
    expect(evidence.text).toContain('invoice.pdf');
    expect(evidence.text).toContain('Your file has been uploaded');
    await post('evidence');
    const taskList = await agent.get('/make-claim/task-list');
    expect(taskList.text).toContain('completed 7 of 7 tasks');
    const fee = await agent.get('/make-claim/fee');
    expect(fee.status, fee.text).toBe(200);
    expect(fee.text).toContain('£80.00');
    const review = await agent.get('/make-claim/check-answers');
    expect(review.status, review.text).toBe(200);
    expect(review.text).toContain('Alex Morgan');
    expect(review.text).toContain('invoice.pdf');
    expect(review.text).toContain('£1,304.46');
    expect(review.text).toContain('/make-claim/amount?change=true');
    expect((await post('check-answers', { statementOfTruth: 'yes' })).headers.location).toBe('/make-claim/payment');
    const pay = await agent.get('/make-claim/payment');
    expect(pay.status, pay.text).toBe(200);
    expect(pay.text).toContain('Pay £80.00 and submit claim');
    expect((await post('payment', payment)).headers.location).toBe('/make-claim/confirmation');
    const confirmation = await agent.get('/make-claim/confirmation');
    expect(confirmation.status, confirmation.text).toBe(200);
    expect(confirmation.text).toContain(`data-claim-reference="${reference}"`);
    expect(confirmation.text).toContain('data-security-code="DEMO2026"');
    expect(confirmation.text).toContain('25 November 2026');
    expect(confirmation.text).toContain('What happens next');
    const issue = calls.find(call => call.path.endsWith('/issue'))!;
    expect(issue.body).toEqual({ paymentReference: expect.stringMatching(/^DEMO-PAY-/), statementOfTruth: true });
    expect(JSON.stringify(calls.filter(call => !(call.body instanceof FormData)))).not.toContain('4242');
    expect((await post('payment', payment)).headers.location).toBe('/make-claim/confirmation');
    expect(calls.filter(call => call.path.endsWith('/issue'))).toHaveLength(1);
    expect((await post('new')).headers.location).toBe('/make-claim/eligibility');
  });

  it('keeps incomplete drafts away from review and payment', async () => {
    await eligibility();
    expect((await agent.get('/make-claim/fee')).headers.location).toBe('/make-claim/task-list');
    expect((await agent.get('/make-claim/payment')).headers.location).toBe('/make-claim/check-answers');
    expect((await post('payment', payment)).headers.location).toBe('/make-claim/check-answers');
    expect(calls.some(call => call.path.endsWith('/issue'))).toBe(false);
  });

  it('retains party values and escapes HTML after validation failure', async () => {
    await eligibility();
    const invalid = await post('your-details', { ...party, name: '<script>alert(1)</script>', postcode: 'wrong', email: 'wrong' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('href="#postcode"');
    expect(invalid.text).toContain('value="14 Example Lane"');
    expect(invalid.text).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(invalid.text).not.toContain('<script>alert(1)</script>');
    expect(calls.filter(call => call.method === 'PUT')).toHaveLength(0);
  });

  it('adds and removes amount and timeline rows without marking the task complete', async () => {
    await eligibility();
    const added = await post('amount', { 'items[0][reason]': 'First item', 'items[0][amount]': '100', action: 'add' });
    expect(added.status, added.text).toBe(200);
    expect(added.text).toContain('items[1][amount]');
    expect(added.text).toContain('First item');
    const removed = await post('amount', { 'items[0][reason]': 'First item', 'items[1][reason]': 'Second item', action: 'remove-0' });
    expect(removed.text).toContain('Second item');
    expect(removed.text).not.toContain('First item');
    const events = await post('timeline', { 'events[0][description]': 'Agreement signed', action: 'add' });
    expect(events.status, events.text).toBe(200);
    expect(events.text).toContain('events[1][day]');
    expect((await agent.get('/make-claim/task-list')).text).toContain('completed 0 of 7 tasks');
  });

  it('validates interest and calendar dates and sorts the saved timeline', async () => {
    await eligibility();
    const interest = await post('interest', { claimed: 'yes', day: '31', month: '2', year: '2026' });
    expect(interest.status).toBe(400);
    expect(interest.text).toContain('href="#interest-date-day"');
    expect((await post('interest', { claimed: 'yes', day: '1', month: '1', year: '2999' })).status).toBe(400);
    expect((await post('interest', { claimed: 'no' })).status).toBe(302);
    expect(calls.at(-1)?.body.interest).toEqual({ claimed: false, rate: 8 });
    const invalid = await post('timeline', { 'events[0][day]': '31', 'events[0][month]': '2', 'events[0][year]': '2026', 'events[0][description]': '' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('href="#event-0-day"');
    await post('timeline', { 'events[0][day]': '2', 'events[0][month]': '1', 'events[0][year]': '2026', 'events[0][description]': 'Later', 'events[1][day]': '1', 'events[1][month]': '1', 'events[1][year]': '2026', 'events[1][description]': 'Earlier' });
    expect(calls.at(-1)?.body.timeline.map((event: any) => event.date)).toEqual(['2026-01-01', '2026-01-02']);
  });

  it('requires CSRF before reading evidence and rejects unsupported or oversized files', async () => {
    await eligibility();
    expect((await agent.post('/make-claim/evidence/upload').attach('file', pdf, 'invoice.pdf')).status).toBe(403);
    expect((await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).attach('file', Buffer.from('not pdf'), { filename: 'fake.pdf', contentType: 'application/pdf' })).status).toBe(415);
    expect((await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).attach('file', Buffer.from('text'), { filename: 'file.txt', contentType: 'text/plain' })).status).toBe(400);
    expect((await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).attach('file', Buffer.alloc(10 * 1024 * 1024 + 1), { filename: 'large.pdf', contentType: 'application/pdf' })).status).toBe(413);
    expect(calls.some(call => call.path.endsWith('/documents'))).toBe(false);
  });

  it('propagates request IDs and lets unexpected upload failures produce the shared 500 page', async () => {
    await eligibility(); failUpload = true;
    const result = await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).set('X-Request-ID', 'b4-upload-outage').attach('file', pdf, { filename: 'invoice.pdf', contentType: 'application/pdf' });
    expect(result.status).toBe(500);
    expect(result.text).toContain('Sorry, there is a problem with the service');
    expect(result.text).toContain('b4-upload-outage');
    expect(calls.at(-1)?.headers.get('X-Request-ID')).toBe('b4-upload-outage');
    expect(result.text).not.toContain('ApiError');
  });

  it('removes draft evidence through the API and never lets a forged document ID through', async () => {
    await eligibility();
    await agent.post('/make-claim/evidence/upload').set('X-CSRF-Token', csrf).attach('file', pdf, { filename: 'invoice.pdf', contentType: 'application/pdf' });
    expect((await post('evidence/remove', { documentId: 'not-owned' })).status).toBe(404);
    expect((await post('evidence/remove', { documentId: documents[0].id })).status).toBe(302);
    expect(documents).toHaveLength(0);
  });

  it('requires statement of truth and invalidates it when answers change', async () => {
    await answers();
    expect((await post('check-answers')).status).toBe(400);
    await post('check-answers', { statementOfTruth: 'yes' });
    const edit = await agent.get('/make-claim/your-details?change=true');
    expect(edit.text).toContain('value="Alex Morgan"');
    expect((await post('your-details?change=true', { ...party, name: 'Updated Citizen' })).headers.location).toBe('/make-claim/check-answers');
    expect((await agent.get('/make-claim/payment')).headers.location).toBe('/make-claim/check-answers');
    expect((await agent.get('/make-claim/check-answers')).text).toContain('Updated Citizen');
  });

  it('rejects real card details without echoing or forwarding them', async () => {
    await answers(); await post('check-answers', { statementOfTruth: 'yes' });
    const invalid = await post('payment', { ...payment, cardNumber: '4111111111111111', securityCode: '987', cardholderName: 'Never Persist This' });
    expect(invalid.status).toBe(400);
    expect(invalid.text).toContain('Use the demo card number');
    expect(invalid.text).not.toContain('4111111111111111');
    expect(invalid.text).not.toContain('Never Persist This');
    expect(calls.some(call => call.path.endsWith('/issue'))).toBe(false);
  });
});

describe('claim form validation', () => {
  it('uses integer pence and enforces the £25,000 total, precision and positive amounts', () => {
    expect(validateAmounts([{ reason: 'A', amount: '0.10' }, { reason: 'B', amount: '0.20' }]).errors).toEqual([]);
    expect(validateAmounts([{ reason: 'A', amount: '25000' }]).errors).toEqual([]);
    for (const amount of ['0', '-1', '1.001', '1e3', 'Infinity', '25000.01']) expect(validateAmounts([{ reason: 'A', amount }]).errors.length).toBeGreaterThan(0);
    expect(validateAmounts([{ reason: 'A', amount: '25000' }, { reason: 'B', amount: '0.01' }]).errors[0].text).toContain('total claim amount');
  });
  it('rejects invalid dates and accepts leap dates', () => {
    expect(readDate({ day: '29', month: '2', year: '2024' })).toBe('2024-02-29');
    expect(readDate({ day: '29', month: '2', year: '2025' })).toBeUndefined();
    expect(readDate({ day: '0', month: '1', year: '2026' })).toBeUndefined();
  });
});
