import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

type Target = { service: string; url: string; requestId: string };
type Configuration = { targets: Target[]; site: string; env: string; version: string; apiKey: string; appKey: string; timeoutMs: number };

function base(value: string): string {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error('Telemetry targets must be HTTP(S) URLs without credentials, queries or fragments');
  }
  return url.href.replace(/\/$/, '');
}

export function configuration(env: NodeJS.ProcessEnv): Configuration {
  for (const key of ['TELEMETRY_BASE_URL', 'DD_VERSION', 'DD_API_KEY', 'DD_APP_KEY']) {
    if (!env[key]) throw new Error(`Set ${key} before verifying telemetry`);
  }
  const site = env.DD_SITE || 'datadoghq.com';
  if (!['datadoghq.com', 'datadoghq.eu', 'us3.datadoghq.com', 'us5.datadoghq.com', 'ap1.datadoghq.com', 'ap2.datadoghq.com', 'ddog-gov.com'].includes(site)) {
    throw new Error('DD_SITE must be a supported Datadog site');
  }
  const environment = env.DD_ENV || 'demo';
  const version = env.DD_VERSION!;
  if (!/^[a-zA-Z0-9_.-]+$/.test(environment) || !/^[a-f0-9]{40}$/.test(version)) {
    throw new Error('Use a safe DD_ENV tag and the full deployed git SHA as DD_VERSION');
  }
  const timeoutMs = Number(env.TELEMETRY_TIMEOUT_SECONDS || 180) * 1000;
  if (!Number.isFinite(timeoutMs) || timeoutMs < 1000 || timeoutMs > 600000) throw new Error('Telemetry timeout must be 1–600 seconds');
  const ui = base(env.TELEMETRY_BASE_URL!);
  const modern = base(env.TELEMETRY_CIVIL_API_URL || ui);
  const legacy = base(env.TELEMETRY_LEGACY_API_URL || `${ui}/legacy`);
  const targets = [
    { service: 'civil-citizen-ui', url: `${ui}/` },
    { service: 'civil-case-api', url: `${modern}/api/claims?page=0&size=1` },
    { service: 'legacy-claim-store', url: `${legacy}/api/claims?page=0&size=1` }
  ].map(target => ({ ...target, requestId: `telemetry-${randomUUID()}` }));
  return { targets, site, env: environment, version, apiKey: env.DD_API_KEY!, appKey: env.DD_APP_KEY!, timeoutMs };
}

export async function verify(config: Configuration, fetcher: typeof fetch = fetch,
  pause: (ms: number) => Promise<unknown> = delay, report: (line: string) => void = console.log): Promise<void> {
  const from = new Date(Date.now() - 30000).toISOString();
  // Unique IDs bind evidence to this target and run, never another VM's traffic.
  for (const target of config.targets) {
    const response = await fetcher(target.url, { redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { 'X-Request-ID': target.requestId, 'X-Datadog-Sampling-Priority': '2' } });
    await response.body?.cancel();
    if (!response.ok) throw new Error(`${target.service}: probe HTTP ${response.status}`);
    if (response.headers.get('x-request-id') !== target.requestId) throw new Error(`${target.service}: request ID was not preserved`);
    report(JSON.stringify({ event: 'probe', ...target }));
  }

  async function search(kind: 'logs' | 'spans', target: Target): Promise<any[]> {
    const query = `service:${target.service} env:${config.env} version:${config.version} @request_id:${target.requestId}`
      + (kind === 'spans' ? ' @span.kind:server' : '');
    const attributes = { filter: { from, to: 'now', query }, page: { limit: 10 }, sort: '-timestamp' };
    const body = kind === 'logs' ? attributes : { data: { type: 'search_request', attributes } };
    const response = await fetcher(`https://api.${config.site}/api/v2/${kind}/events/search`, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { 'Content-Type': 'application/json', 'DD-API-KEY': config.apiKey, 'DD-APPLICATION-KEY': config.appKey },
      body: JSON.stringify(body)
    });
    // Never print response bodies: authorization errors can contain sensitive details.
    if (!response.ok) throw new Error(`Datadog ${kind} search HTTP ${response.status}`);
    const result = await response.json() as { data?: any[]; meta?: { status?: string } };
    if (!Array.isArray(result.data) || result.meta?.status === 'timeout') throw new Error(`Datadog ${kind} search incomplete`);
    return result.data;
  }

  const pending = new Map(config.targets.map(target => [target.service, target]));
  const deadline = Date.now() + config.timeoutMs;
  do {
    for (const [service, target] of pending) {
      const logs = await search('logs', target);
      const spans = await search('spans', target);
      if (logs.length && spans.length) {
        report(JSON.stringify({ event: 'verified', ...target, env: config.env, version: config.version,
          logId: logs[0].id, traceId: spans[0].attributes?.trace_id, spanId: spans[0].attributes?.span_id }));
        pending.delete(service);
      }
    }
    if (!pending.size) return;
    if (Date.now() >= deadline) break;
    await pause(Math.min(10000, deadline - Date.now()));
  } while (Date.now() < deadline);
  throw new Error(`Telemetry NOT verified for ${[...pending.keys()].join(', ')}. Healthy HTTP alone is not on-call readiness; check the target's agent, log collector, tags and indexing.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  verify(configuration(process.env)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
