import express, { type ErrorRequestHandler } from 'express';
import session from 'express-session';
import createMemoryStore from 'memorystore';
import nunjucks from 'nunjucks';
import helmet from 'helmet';
import path from 'node:path';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { featuresRoot, featureDirectories, registerFeatureRoutes } from './route-loader';
import { logger } from './observability/logger';

export function createApp(): express.Express {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1);
  app.use(helmet({ contentSecurityPolicy: { directives: { scriptSrc: ["'self'"], upgradeInsecureRequests: null } } }));
  app.use((req, res, next) => {
    const supplied = req.get('X-Request-ID');
    req.requestId = supplied && /^[A-Za-z0-9_-]{1,100}$/.test(supplied) ? supplied : randomUUID();
    res.setHeader('X-Request-ID', req.requestId);
    const started = performance.now();
    // Mounted routers temporarily strip their prefix from req.path.
    const requestPath = req.path;
    res.on('finish', () => logger.info({ request_id: req.requestId, method: req.method,
      path: requestPath, status: res.statusCode, duration_ms: Math.round(performance.now() - started) }, 'HTTP request'));
    next();
  });
  app.get('/health', (_req, res) => res.json({ status: 'UP', service: 'civil-citizen-ui' }));
  app.use('/assets', express.static(path.join(__dirname, 'public')));
  for (const feature of featureDirectories()) {
    app.use(`/assets/features/${feature}`, express.static(path.join(featuresRoot, feature, 'assets')));
  }
  app.use(express.urlencoded({ extended: true, limit: '1mb' }));
  app.use(express.json({ limit: '1mb' }));
  const secret = process.env.SESSION_SECRET || (process.env.NODE_ENV === 'production' ? '' : 'local-demo-session-only');
  if (!secret) throw new Error('SESSION_SECRET is required in production');
  const MemoryStore = createMemoryStore(session);
  app.use(session({ secret, name: 'cmc.sid', resave: false, saveUninitialized: false,
    store: new MemoryStore({ checkPeriod: 86400000 }),
    cookie: { httpOnly: true, sameSite: 'lax', secure: process.env.COOKIE_SECURE === 'true', maxAge: 8 * 60 * 60 * 1000 } }));
  const env = nunjucks.configure([
    path.join(__dirname, 'views'),
    ...featureDirectories().map(name => path.join(featuresRoot, name, 'views')),
    path.join(process.cwd(), 'node_modules/govuk-frontend/dist')
  ], { autoescape: true, express: app, noCache: process.env.NODE_ENV !== 'production' });
  app.set('view engine', 'njk');
  env.addGlobal('serviceName', 'Make a money claim');
  env.addGlobal('demoBanner', 'DEMO – this is not a real government service');
  env.addFilter('money', (value: number) => new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' }).format(Number(value || 0)));
  env.addFilter('date', (value: string | Date) => value ? new Intl.DateTimeFormat('en-GB', { dateStyle: 'long', timeZone: 'Europe/London' }).format(new Date(value)) : 'Not provided');
  env.addFilter('statusLabel', (value: string) => (value || '').toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase()));
  app.use((req, res, next) => {
    req.session.csrfToken ||= randomBytes(24).toString('hex');
    Object.assign(res.locals, { requestId: req.requestId, user: req.session.user, currentPath: req.path, csrfToken: req.session.csrfToken });
    // Header tokens support multipart uploads before the file parser runs.
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method)) {
      const supplied = req.get('X-CSRF-Token') || req.body?._csrf;
      const expected = req.session.csrfToken;
      if (typeof supplied !== 'string' || supplied.length !== expected.length || !timingSafeEqual(Buffer.from(supplied), Buffer.from(expected))) {
        return res.status(403).render('errors/forbidden.njk');
      }
    }
    next();
  });
  registerFeatureRoutes(app);
  app.get('/', (_req, res) => res.render('home.njk'));
  app.use((_req, res) => res.status(404).render('errors/not-found.njk'));
  const errorHandler: ErrorRequestHandler = (error, req, res, _next) => {
    logger.error({ request_id: req.requestId, 'error.kind': error.name, 'error.message': error.message, 'error.stack': error.stack }, 'Unhandled request error');
    res.status(500).render('errors/service.njk', { requestId: req.requestId });
  };
  app.use(errorHandler);
  return app;
}
