import { Router, type Express, type Request, type Response } from 'express';
import { ApiError, apiContext, apiForReference, getCombinedClaims } from '../../lib/api-client';
import { canRequestJudgment, errorList, referencePattern, responseLabels, paymentLabels, textValue,
  type Claim, type ClaimEvent, type FieldErrors } from '../respond/model';

const pageSize = 10;
async function ownedClaim(req: Request, res: Response): Promise<Claim | undefined> {
  const reference = textValue(req.params.reference).toUpperCase();
  if (!referencePattern.test(reference)) { res.status(404).render('errors/not-found.njk'); return; }
  try {
    const claim = await apiForReference(reference).get<Claim>(`/api/claims/${reference}`, apiContext(req));
    // The demo identity still scopes claimant screens; a guessed reference is not enough.
    if (claim.claimantId !== req.session.user!.id) { res.status(404).render('errors/not-found.njk'); return; }
    return claim;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) { res.status(404).render('errors/not-found.njk'); return; }
    throw error;
  }
}
function judgmentPage(res: Response, claim: Claim, message?: string, status = 200) {
  const fields: FieldErrors = message ? { statementOfTruth: message } : {};
  return res.status(status).render('dashboard/judgment.njk', {
    claim, eligible: canRequestJudgment(claim), fields, errors: errorList(fields), backLink: `/dashboard/${claim.reference}`
  });
}

export function registerRoutes(app: Express): void {
  const router = Router();
  router.use((req, res, next) => {
    if (!req.session.user) { req.session.returnTo = req.originalUrl; return res.redirect('/sign-in'); }
    if (req.session.user.role !== 'claimant') return res.status(403).render('errors/forbidden.njk');
    next();
  });
  router.get('/', async (req, res) => {
    const pageValue = textValue(req.query.page) || '0';
    if (!/^\d{1,3}$/.test(pageValue)) return res.redirect('/dashboard');
    const page = Number(pageValue);
    const claims = await getCombinedClaims({ claimantId: req.session.user!.id, page, size: pageSize }, apiContext(req));
    if (page > 0 && page * pageSize >= claims.total) return res.redirect('/dashboard');
    res.render('dashboard/index.njk', {
      claims: claims.items.map(item => ({ ...item, canRequestJudgment: canRequestJudgment(item as Claim) })),
      total: claims.total, page, pageSize, start: page * pageSize + 1, end: Math.min((page + 1) * pageSize, claims.total),
      previousPage: page > 0 ? `/dashboard?page=${page - 1}` : undefined,
      nextPage: (page + 1) * pageSize < claims.total ? `/dashboard?page=${page + 1}` : undefined
    });
  });
  router.get('/:reference', async (req, res) => {
    const claim = await ownedClaim(req, res);
    if (!claim) return;
    const events = await apiForReference(claim.reference).get<ClaimEvent[]>(`/api/claims/${claim.reference}/events`, apiContext(req));
    res.render('dashboard/detail.njk', { claim, events, eligible: canRequestJudgment(claim), responseLabels, paymentLabels, backLink: '/dashboard' });
  });
  router.get('/:reference/default-judgment', async (req, res) => {
    const claim = await ownedClaim(req, res);
    if (claim) judgmentPage(res, claim);
  });
  router.post('/:reference/default-judgment', async (req, res) => {
    const claim = await ownedClaim(req, res);
    if (!claim) return;
    // Always recheck eligibility, even when the form was opened before a response arrived.
    if (!canRequestJudgment(claim)) return judgmentPage(res, claim, undefined, 409);
    if (req.body.statementOfTruth !== 'true') return judgmentPage(res, claim, 'Confirm that the defendant has not paid the amount you are requesting', 400);
    try {
      await apiForReference(claim.reference).post<Claim>(`/api/claims/${claim.reference}/default-judgment`, { statementOfTruth: true }, apiContext(req));
      res.redirect(`/dashboard/${claim.reference}/judgment-confirmation`);
    } catch (error) {
      if (error instanceof ApiError && [400, 409].includes(error.status)) {
        const latest = await ownedClaim(req, res);
        if (latest) return judgmentPage(res, latest, 'The claim has changed. Review its current status before trying again.', error.status);
        return;
      }
      throw error;
    }
  });
  router.get('/:reference/judgment-confirmation', async (req, res) => {
    const claim = await ownedClaim(req, res);
    if (!claim) return;
    if (!claim.judgmentRequestedAt) return res.redirect(`/dashboard/${claim.reference}`);
    res.render('dashboard/confirmation.njk', { claim });
  });
  app.use('/dashboard', router);
}
