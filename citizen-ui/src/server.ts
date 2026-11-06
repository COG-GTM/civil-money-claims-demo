import { createApp } from './app';
import { logger } from './observability/logger';
const port = Number(process.env.PORT || 3000);
const server = createApp().listen(port, '0.0.0.0', () => logger.info({ port }, 'Citizen UI listening'));
process.on('SIGTERM', () => server.close());
