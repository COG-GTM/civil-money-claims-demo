import type { Express } from 'express';
import { apiContext, civilApi, legacyApi } from '../../lib/api-client';
import { logger } from '../../observability/logger';
import { requireCaseworker } from '../sign-in/access';
import { dateTime } from '../caseworker/models';

interface LatestError { timestamp: string; requestId: string; method?: string; path?: string; message: string; status?: number; }
interface OpsMetrics { service: string; windowSeconds?: number; requestCount?: number; errorCount?: number; requestRate: number; errorRate: number; latestErrors: LatestError[]; }
interface ResetResult { status: 'reset'; claims: number; }
const services = [{ label: 'Civil case API', client: civilApi }, { label: 'Legacy claim store', client: legacyApi }];
const resetEnabled = (): boolean => process.env.DEMO_RESET_ENABLED === 'true' && Boolean(process.env.DEMO_RESET_TOKEN);

export function registerRoutes(app: Express): void {
  app.use('/ops', requireCaseworker);
  app.get('/ops', async (req, res) => {
    // Failed upstream metrics remain errors, never fabricated zero-traffic health cards.
    const metrics = await Promise.all(services.map(async ({ label, client }) => {
      const metric = await client.get<OpsMetrics>('/api/ops/metrics', apiContext(req));
      return { ...metric, label, requestRateLabel: metric.requestRate.toFixed(2), errorRateLabel: (metric.errorRate * 100).toFixed(1) };
    }));
    const latestErrors = metrics.flatMap(metric => metric.latestErrors.map(error => ({ ...error, service: metric.service })))
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp)).slice(0, 20)
      .map(error => ({ ...error, dateLabel: dateTime(error.timestamp) }));
    const reset = req.session.opsReset;
    delete req.session.opsReset;
    res.render('ops/index.njk', { pageTitle: 'Service operations', metrics, latestErrors, resetEnabled: resetEnabled(), reset, updatedAt: dateTime(new Date().toISOString()) });
  });

  app.get('/ops/reset', (_req, res) => {
    res.status(resetEnabled() ? 200 : 403).render('ops/reset.njk', { pageTitle: 'Reset demo data', resetEnabled: resetEnabled(), backLink: '/ops' });
  });

  app.post('/ops/reset', async (req, res) => {
    if (!resetEnabled()) { res.status(403).render('ops/reset.njk', { pageTitle: 'Demo reset is disabled', resetEnabled: false }); return; }
    if (req.body?.confirm !== 'yes') {
      res.status(400).render('ops/reset.njk', { pageTitle: 'Error: Reset demo data', resetEnabled: true, backLink: '/ops',
        confirmError: 'Confirm that you want to replace all demo claims', errors: [{ text: 'Confirm that you want to replace all demo claims', href: '#confirm' }] });
      return;
    }
    // The token stays server-side; each store resets in its own transaction, not a distributed transaction.
    const context = { ...apiContext(req), resetToken: process.env.DEMO_RESET_TOKEN };
    const results = await Promise.allSettled(services.map(({ client }) => client.post<ResetResult>('/api/ops/reset', {}, context)));
    const outcomes = results.map((result, index) => {
      if (result.status === 'fulfilled') return { label: services[index].label, success: true, claims: result.value.claims };
      const error = result.reason instanceof Error ? result.reason : new Error('Demo reset failed');
      logger.error({ request_id: req.requestId, service_name: services[index].label, 'error.kind': error.name, 'error.message': error.message, 'error.stack': error.stack }, 'Demo reset failed');
      return { label: services[index].label, success: false };
    });
    if (outcomes.some(outcome => !outcome.success)) {
      res.status(502).render('ops/reset-result.njk', { pageTitle: 'Demo reset did not complete', outcomes });
      return;
    }
    delete req.session.makeClaim;
    delete req.session.respond;
    delete req.session.caseworkerNoteSaved;
    req.session.opsReset = { claims: outcomes.reduce((total, outcome) => total + (outcome.claims || 0), 0) };
    res.redirect(303, '/ops');
  });
}
