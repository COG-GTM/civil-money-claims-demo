#!/usr/bin/env node
// Node 24 runs this TypeScript directly; no extra package install is required.
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export type TrafficConfig = { baseUrl: string; rate: number; concurrency: number; iterations: number; durationSeconds: number };
export function configuration(env: NodeJS.ProcessEnv = process.env): TrafficConfig {
  const baseUrl = new URL(env.TRAFFIC_BASE_URL || 'http://localhost:3000');
  if (!['http:', 'https:'].includes(baseUrl.protocol) || baseUrl.username || baseUrl.password) throw new Error('TRAFFIC_BASE_URL must be an HTTP(S) URL without credentials');
  function number(name: string, fallback: number, min: number, max: number, integer = false) {
    const value = Number(env[name] ?? fallback);
    if (!Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) throw new Error(`Invalid ${name}: expected ${min}..${max}`);
    return value;
  }
  return { baseUrl: baseUrl.href.replace(/\/$/, ''),
    rate: number('TRAFFIC_RATE_PER_MINUTE', 6, 0.1, 600),
    concurrency: number('TRAFFIC_CONCURRENCY', 4, 1, 50, true),
    iterations: number('TRAFFIC_ITERATIONS', 0, 0, 1000000, true),
    durationSeconds: number('TRAFFIC_DURATION_SECONDS', 0, 0, 86400) };
}

const decode = (s: string) => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&#39;', "'");
export function csrfToken(html: string): string {
  for (const tag of html.match(/<(?:meta|input)\b[^>]*>/gi) || []) {
    const attrs = Object.fromEntries([...tag.matchAll(/([\w-]+)\s*=\s*["']([^"']*)["']/g)].map(m => [m[1], decode(m[2])]));
    if (attrs.name === 'csrf-token') return attrs.content;
    if (attrs.name === '_csrf') return attrs.value;
  }
  throw new Error('Page did not contain a CSRF token');
}

export class BrowserSession {
  private cookies = new Map<string, string>();
  csrf = '';
  readonly baseUrl: string;
  readonly signal?: AbortSignal;
  constructor(baseUrl: string, signal?: AbortSignal) { this.baseUrl = baseUrl; this.signal = signal; }

  // Follow only same-origin redirects; do not leak session cookies to another host.
  async request(path: string, init: RequestInit = {}): Promise<{ html: string; url: URL }> {
    let url = new URL(path, this.baseUrl);
    const origin = new URL(this.baseUrl).origin;
    for (let hop = 0; hop < 10; hop++) {
      if (url.origin !== origin) throw new Error('Refused cross-origin redirect');
      const requestId = `traffic-${randomUUID()}`;
      const headers = new Headers(init.headers);
      headers.set('X-Request-ID', requestId);
      headers.set('Cookie', [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '));
      const timeout = AbortSignal.timeout(30000);
      const response = await fetch(url, { ...init, headers, redirect: 'manual', signal: this.signal ? AbortSignal.any([this.signal, timeout]) : timeout });
      for (const cookie of response.headers.getSetCookie()) {
        const pair = cookie.split(';', 1)[0];
        const separator = pair.indexOf('=');
        if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
      }
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        const location = response.headers.get('location');
        await response.arrayBuffer();
        if (!location) throw new Error('Redirect without Location');
        url = new URL(location, url);
        if ([301, 302, 303].includes(response.status)) init = { method: 'GET' };
        continue;
      }
      const html = await response.text();
      if (!response.ok) throw new Error(`${init.method || 'GET'} ${url.pathname}: HTTP ${response.status}; request_id=${response.headers.get('x-request-id') || requestId}`);
      if (/<div\b[^>]*class=["'][^"']*govuk-error-summary/.test(html)) throw new Error(`Validation rejected ${url.pathname}; request_id=${response.headers.get('x-request-id') || requestId}`);
      if (/<(?:input|meta)\b[^>]*name=["'](?:_csrf|csrf-token)/.test(html)) this.csrf = csrfToken(html);
      return { html, url };
    }
    throw new Error('Too many redirects');
  }

  async form(path: string, fields: Record<string, string> = {}) {
    await this.request(path);
    return this.request(path, { method: 'POST', body: new URLSearchParams({ ...fields, _csrf: this.csrf }) });
  }
}

export async function makeClaim(baseUrl: string, iteration: number, signal?: AbortSignal): Promise<{ reference: string; securityCode: string }> {
  const browser = new BrowserSession(baseUrl, signal);
  await browser.form('/sign-in', { role: 'claimant' });
  for (const [suffix, name] of [['', 'over18'], ['/location', 'englandWales'], ['/amount', 'underLimit'], ['/parties', 'oneDefendant']]) {
    await browser.form(`/make-claim/eligibility${suffix}`, { [name]: 'yes' });
  }
  for (const [step, name] of [['your-details', 'Amelia Clarke'], ['defendant-details', 'Oliver Hughes']]) {
    await browser.form(`/make-claim/${step}`, { type: 'INDIVIDUAL', name, email: `${step}@example.test`, phone: '07700 900123',
      line1: '24 Cedar Close', line2: '', city: 'Bristol', postcode: 'BS1 4ST', country: 'England' });
  }
  await browser.form('/make-claim/amount', { 'items[0][reason]': 'Unpaid decorating invoice', 'items[0][amount]': `${250 + iteration % 100}.00` });
  const invoiceDate = new Date(Date.now() - 45 * 86400000);
  const day = String(invoiceDate.getUTCDate()), month = String(invoiceDate.getUTCMonth() + 1), year = String(invoiceDate.getUTCFullYear());
  await browser.form('/make-claim/interest', { claimed: 'yes', day, month, year });
  await browser.form('/make-claim/reason', { reason: 'Synthetic demo: I completed the agreed decorating work. The invoice remains unpaid despite two written reminders.' });
  await browser.form('/make-claim/timeline', { 'events[0][day]': day, 'events[0][month]': month, 'events[0][year]': year,
    'events[0][description]': 'Work completed and invoice sent to the defendant.' });
  await browser.request('/make-claim/evidence');
  const name = iteration % 2 ? 'sample-invoice.pdf' : 'sample-receipt.png';
  const data = await readFile(new URL(`../seed/evidence/${name}`, import.meta.url));
  const upload = new FormData();
  upload.append('file', new Blob([new Uint8Array(data)], { type: name.endsWith('.pdf') ? 'application/pdf' : 'image/png' }), name);
  await browser.request('/make-claim/evidence/upload', { method: 'POST', headers: { 'X-CSRF-Token': browser.csrf }, body: upload });
  await browser.form('/make-claim/evidence');
  await browser.request('/make-claim/fee');
  await browser.form('/make-claim/check-answers', { statementOfTruth: 'yes' });
  // Only the explicitly documented synthetic demo card is used; never print form bodies.
  const confirmation = await browser.form('/make-claim/payment', { cardNumber: '4242424242424242', cardholderName: 'Demo Citizen',
    expiryMonth: '12', expiryYear: String(new Date().getUTCFullYear() + 4), securityCode: '123' });
  if (confirmation.url.pathname !== '/make-claim/confirmation') throw new Error('Claim did not reach confirmation');
  const reference = confirmation.html.match(/000MC\d{3,}/)?.[0];
  const securityCode = confirmation.html.match(/data-security-code=["']([A-Z0-9]{8})["']/)?.[1];
  if (!reference || !securityCode) throw new Error('Claim confirmation is missing reference or data-security-code');
  return { reference, securityCode };
}

export async function respond(baseUrl: string, claim: { reference: string; securityCode: string }, iteration: number, signal?: AbortSignal) {
  const browser = new BrowserSession(baseUrl, signal);
  await browser.form('/respond', claim);
  const type = ['ADMIT_ALL', 'ADMIT_PART', 'REJECT'][iteration % 3];
  await browser.form('/respond/response', { type });
  if (type !== 'ADMIT_ALL') await browser.form('/respond/details', { reason: 'Synthetic demo: I disagree with the amount claimed because some agreed work was incomplete.', admittedAmount: '100.00' });
  if (type !== 'REJECT') await browser.form('/respond/payment', { paymentOption: 'IMMEDIATELY' });
  const result = await browser.form('/respond/check-answers', { statementOfTruth: 'true' });
  if (result.url.pathname !== '/respond/confirmation' || !result.html.includes(claim.reference)) throw new Error('Response did not reach confirmation');
}

export async function run(config: TrafficConfig, signal: AbortSignal) {
  const start = performance.now();
  let scheduled = 0, passed = 0, failed = 0, skipped = 0;
  const active = new Set<Promise<void>>();
  const log = (fields: object) => console.log(JSON.stringify({ timestamp: new Date().toISOString(), service: 'demo-traffic', ...fields }));
  log({ event: 'start', ...config });
  while (!signal.aborted && (!config.iterations || scheduled < config.iterations)
    && (!config.durationSeconds || performance.now() - start < config.durationSeconds * 1000)) {
    if (active.size >= config.concurrency) { skipped++; }
    else {
      const iteration = scheduled++;
      const job = (async () => {
        try {
          const claim = await makeClaim(config.baseUrl, iteration, signal);
          await respond(config.baseUrl, claim, iteration, signal);
          passed++;
          log({ event: 'journey_passed', iteration, reference: claim.reference });
        } catch (error) {
          if (!signal.aborted) { failed++; log({ event: 'journey_failed', iteration, message: error instanceof Error ? error.message : 'Unknown failure' }); }
        }
      })();
      active.add(job);
      void job.finally(() => active.delete(job));
    }
    if (config.iterations && scheduled >= config.iterations) break;
    await delay(60000 / config.rate, undefined, { signal }).catch(() => {});
  }
  await Promise.all(active);
  log({ event: 'summary', scheduled, passed, failed, skipped, duration_ms: Math.round(performance.now() - start) });
  return failed ? 1 : 0;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv.includes('--help')) console.log('Run with Node 24: node scripts/traffic.ts\nTRAFFIC_BASE_URL (UI origin), TRAFFIC_RATE_PER_MINUTE (journeys/min, default 6), TRAFFIC_CONCURRENCY (default 4), TRAFFIC_ITERATIONS or TRAFFIC_DURATION_SECONDS (0 = unlimited). Ctrl-C stops cleanly. Uses synthetic claims and a mock card; never run against a real government service.');
  else {
    const controller = new AbortController();
    process.once('SIGINT', () => controller.abort());
    process.once('SIGTERM', () => controller.abort());
    try { process.exitCode = await run(configuration(), controller.signal); }
    catch (error) { console.error(error instanceof Error ? error.message : 'Traffic driver failed'); process.exitCode = 1; }
  }
}
