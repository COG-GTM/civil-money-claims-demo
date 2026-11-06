import { describe, it, expect } from 'vitest';
import { createLogger } from './logger';

describe('structured logs', () => {
  it('keeps correlation and exception fields without sensitive payloads', () => {
    const lines: string[] = [];
    const logger = createLogger({ write: line => { lines.push(line); } });
    logger.error({ request_id: 'request-123', dd: { trace_id: '123', span_id: '456' },
      err: new Error('synthetic failure'), securityCode: 'SECRET12', cardNumber: 'sensitive-card',
      body: { content: 'document bytes' }, session: { token: 'secret' } }, 'Request failed');
    const log = JSON.parse(lines[0]);
    expect(log).toMatchObject({ level: 'error', service: 'civil-citizen-ui', env: 'demo',
      request_id: 'request-123', 'dd.trace_id': '123', 'dd.span_id': '456', 'error.kind': 'Error' });
    expect(log['error.stack']).toContain('synthetic failure');
    expect(log.timestamp).toMatch(/^\d{4}-/);
    for (const forbidden of ['SECRET12', 'sensitive-card', 'document bytes', 'token']) expect(lines[0]).not.toContain(forbidden);
  });
  it('emits an explicit inactive trace for non-request logs', () => {
    const lines: string[] = [];
    createLogger({ write: line => { lines.push(line); } }).info('Ready');
    expect(JSON.parse(lines[0])).toMatchObject({ 'dd.trace_id': '0', 'dd.span_id': '0', request_id: null });
  });
});
