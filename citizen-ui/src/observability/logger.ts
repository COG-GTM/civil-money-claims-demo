import pino from 'pino';
import tracer from 'dd-trace';
import type { DestinationStream } from 'pino';

// Keep request logs useful without ever serialising requests, sessions or evidence.
export function createLogger(destination?: DestinationStream) { return pino({
  base: { service: process.env.DD_SERVICE || 'civil-citizen-ui', env: process.env.DD_ENV || 'demo', version: process.env.DD_VERSION || 'local' },
  messageKey: 'message', timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
  formatters: {
    level: label => ({ level: label }),
    log: fields => {
      const span = tracer.scope().active();
      const correlation: Record<string, any> = {};
      if (span) tracer.inject(span.context(), 'log', correlation);
      const dd = (fields.dd || correlation.dd || {}) as Record<string, string>;
      const { dd: _dd, err, ...safe } = fields;
      if (err instanceof Error) {
        Object.assign(safe, { 'error.kind': err.name, 'error.message': err.message, 'error.stack': err.stack });
      }
      return { request_id: null, ...safe, 'dd.trace_id': dd.trace_id || '0', 'dd.span_id': dd.span_id || '0' };
    }
  },
  redact: { paths: [
    'req', 'res', 'body', 'headers', 'session', 'claimant', 'defendant', 'file', 'content',
    'securityCode', 'security_code', 'password', 'token', 'cardNumber', 'cvv',
    '*.securityCode', '*.security_code', '*.password', '*.token', '*.cardNumber', '*.cvv'
  ], remove: true }
}, destination); }

export const logger = createLogger();
