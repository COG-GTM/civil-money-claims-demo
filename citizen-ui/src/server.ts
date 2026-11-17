import { createApp } from './app';
import { normalizeBasePath } from './lib/base-path';
import { warmUp } from './lib/warm-up';
import { logger } from './observability/logger';
const port = Number(process.env.PORT || 3000);
const app = createApp();
warmUp(app, `${normalizeBasePath(process.env.BASE_PATH || '')}/`)
  .then(status => logger.info({ status }, 'Citizen UI warmed up'))
  .catch(err => logger.warn({ err }, 'Citizen UI warm-up failed'))
  .finally(() => {
    const server = app.listen(port, '0.0.0.0', () => logger.info({ port }, 'Citizen UI listening'));
    process.on('SIGTERM', () => server.close());
  });
