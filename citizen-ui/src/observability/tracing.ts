// Loaded before Express so Datadog can instrument modules as they are imported.
import tracer from 'dd-trace';
process.env.DD_TRACE_ENABLED ||= 'false';
process.env.DD_VERSION ||= process.env.RENDER_GIT_COMMIT || 'local';
tracer.init({ logInjection: true,
  service: process.env.DD_SERVICE || 'civil-citizen-ui', env: process.env.DD_ENV || 'demo',
  version: process.env.DD_VERSION || 'local' });
// Routes can contain query-string input; never put it in APM resource names.
tracer.use('http', { headers: ['x-request-id'] });
