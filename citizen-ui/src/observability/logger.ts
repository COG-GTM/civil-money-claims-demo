import pino from 'pino';
export const logger = pino({
  base: { service: process.env.DD_SERVICE || 'civil-citizen-ui', env: process.env.DD_ENV || 'demo', version: process.env.DD_VERSION || 'local' },
  messageKey: 'message', timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
  formatters: { level: label => ({ level: label }) },
  redact: ['securityCode', 'password', 'token', 'cardNumber', 'cvv']
});
