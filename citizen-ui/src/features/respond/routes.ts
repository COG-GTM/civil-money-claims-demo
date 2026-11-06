import { Router, type Express, type Request, type Response } from 'express';
import { ApiError, apiContext, apiForReference } from '../../lib/api-client';
import { canRespond, errorList, paymentLabels, referencePattern, responseLabels, responsePayload,
  textValue, validateDetails, validatePayment, type Claim, type FieldErrors, type RespondSession, type ResponseType, type PaymentOption } from './model';

const journey = (req: Request) => req.session.respond as RespondSession | undefined;
function renderForm(res: Response, page: string, data: object, fields: FieldErrors = {}) {
  return res.status(Object.keys(fields).length ? 400 : 200).render(`respond/${page}.njk`, {
    ...data, fields, errors: errorList(fields), responseLabels, paymentLabels
  });
}
async function currentClaim(req: Request): Promise<Claim> {
  const reference = journey(req)!.reference;
  return apiForReference(reference).get<Claim>(`/api/claims/${reference}`, apiContext(req));
}
function unavailable(res: Response, claim: Claim) {
  return res.status(409).render('respond/unavailable.njk', { claim, backLink: '/respond/claim' });
}

export function registerRoutes(app: Express): void {
  const router = Router();
  router.get('/', (_req, res) => renderForm(res, 'lookup', { values: {}, backLink: '/' }));
  router.post('/', async (req, res) => {
    const values = { reference: textValue(req.body.reference).toUpperCase(), securityCode: textValue(req.body.securityCode).toUpperCase() };
    const fields: FieldErrors = {};
    if (!referencePattern.test(values.reference)) fields.reference = 'Enter a claim reference, for example 000MC001 or 000LC001';
    if (!/^[A-Z0-9]{8}$/.test(values.securityCode)) fields.securityCode = 'Enter the 8-character security code from your claim letter';
    if (Object.keys(fields).length) return renderForm(res, 'lookup', { values, backLink: '/' }, fields);
    // A fresh lookup must not retain another claim's draft answers or verification.
    delete req.session.respond;
    try {
      const claim = await apiForReference(values.reference).post<Claim>('/api/claims/lookup', values, apiContext(req));
      req.session.respond = { reference: claim.reference, answers: {} } satisfies RespondSession;
      res.redirect('/respond/claim');
    } catch (error) {
      if (error instanceof ApiError && [400, 403, 404].includes(error.status)) {
        return renderForm(res, 'lookup', { values, backLink: '/' }, { reference: 'We could not find a claim with that reference and security code. Check your letter and try again.' });
      }
      throw error;
    }
  });

  // Every subsequent step requires a successful code lookup in this session.
  router.use((req, res, next) => journey(req) ? next() : res.redirect('/respond'));
  router.get('/claim', async (req, res) => {
    const claim = await currentClaim(req);
    res.render('respond/claim.njk', { claim, canRespond: canRespond(claim), backLink: '/respond' });
  });
  router.get('/response', async (req, res) => {
    const claim = await currentClaim(req);
    if (!canRespond(claim)) return unavailable(res, claim);
    renderForm(res, 'response', { claim, answers: journey(req)!.answers, backLink: '/respond/claim' });
  });
  router.post('/response', async (req, res) => {
    const claim = await currentClaim(req);
    if (!canRespond(claim)) return unavailable(res, claim);
    const type = textValue(req.body.type) as ResponseType;
    if (!Object.hasOwn(responseLabels, type)) return renderForm(res, 'response', { claim, answers: {}, backLink: '/respond/claim' }, { type: 'Select your response to the claim' });
    const state = journey(req)!;
    if (state.answers.type !== type) {
      state.answers = { type };
      state.detailsComplete = false;
      state.paymentComplete = false;
    }
    res.redirect(type === 'ADMIT_ALL' ? '/respond/payment' : '/respond/details');
  });
  router.use((req, res, next) => {
    if (req.path === '/confirmation' || journey(req)!.answers.type) return next();
    res.redirect('/respond/response');
  });
  router.get('/details', async (req, res) => {
    const state = journey(req)!;
    if (state.answers.type === 'ADMIT_ALL') return res.redirect('/respond/payment');
    renderForm(res, 'details', { claim: await currentClaim(req), answers: state.answers, backLink: '/respond/response' });
  });
  router.post('/details', async (req, res) => {
    const state = journey(req)!;
    if (state.answers.type === 'ADMIT_ALL') return res.redirect('/respond/payment');
    const claim = await currentClaim(req);
    if (!canRespond(claim)) return unavailable(res, claim);
    state.answers.reason = textValue(req.body.reason);
    if (state.answers.type === 'ADMIT_PART') state.answers.admittedAmount = textValue(req.body.admittedAmount);
    const fields = validateDetails(state.answers, claim);
    state.detailsComplete = !Object.keys(fields).length;
    if (!state.detailsComplete) return renderForm(res, 'details', { claim, answers: state.answers, backLink: '/respond/response' }, fields);
    res.redirect(state.answers.type === 'REJECT' ? '/respond/check-answers' : '/respond/payment');
  });
  router.get('/payment', async (req, res) => {
    const state = journey(req)!;
    if (state.answers.type === 'REJECT') return res.redirect('/respond/details');
    if (state.answers.type === 'ADMIT_PART' && !state.detailsComplete) return res.redirect('/respond/details');
    renderForm(res, 'payment', { claim: await currentClaim(req), answers: state.answers, backLink: state.answers.type === 'ADMIT_ALL' ? '/respond/response' : '/respond/details' });
  });
  router.post('/payment', async (req, res) => {
    const state = journey(req)!;
    if (state.answers.type === 'REJECT' || (state.answers.type === 'ADMIT_PART' && !state.detailsComplete)) return res.redirect('/respond/details');
    const claim = await currentClaim(req);
    if (!canRespond(claim)) return unavailable(res, claim);
    const option = textValue(req.body.paymentOption) as PaymentOption;
    Object.assign(state.answers, { paymentOption: option,
      paymentDay: option === 'BY_DATE' ? textValue(req.body.paymentDay) : undefined,
      paymentMonth: option === 'BY_DATE' ? textValue(req.body.paymentMonth) : undefined,
      paymentYear: option === 'BY_DATE' ? textValue(req.body.paymentYear) : undefined,
      instalmentAmount: option === 'INSTALMENTS' ? textValue(req.body.instalmentAmount) : undefined });
    const fields = validatePayment(state.answers, claim);
    state.paymentComplete = !Object.keys(fields).length;
    if (!state.paymentComplete) return renderForm(res, 'payment', { claim, answers: state.answers, backLink: state.answers.type === 'ADMIT_ALL' ? '/respond/response' : '/respond/details' }, fields);
    res.redirect('/respond/check-answers');
  });
  async function checkAnswers(req: Request, res: Response, submit: boolean) {
    const state = journey(req)!;
    if (state.confirmation) return res.redirect('/respond/confirmation');
    if (state.answers.type !== 'ADMIT_ALL' && !state.detailsComplete) return res.redirect('/respond/details');
    if (state.answers.type !== 'REJECT' && !state.paymentComplete) return res.redirect('/respond/payment');
    const claim = await currentClaim(req);
    if (!canRespond(claim)) return unavailable(res, claim);
    // Revalidate saved answers against the latest claim and today's date before submitting.
    if (Object.keys(validateDetails(state.answers, claim)).length) return res.redirect('/respond/details');
    if (state.answers.type !== 'REJECT' && Object.keys(validatePayment(state.answers, claim)).length) return res.redirect('/respond/payment');
    const data = { claim, answers: state.answers, response: responsePayload(state.answers, claim), backLink: state.answers.type === 'REJECT' ? '/respond/details' : '/respond/payment' };
    if (!submit) return renderForm(res, 'check-answers', data);
    if (req.body.statementOfTruth !== 'true') return renderForm(res, 'check-answers', data, { statementOfTruth: 'Confirm that you believe the facts stated in your response are true' });
    try {
      const confirmed = await apiForReference(state.reference).post<Claim>(`/api/claims/${state.reference}/response`, data.response, apiContext(req));
      state.confirmation = confirmed;
      res.redirect('/respond/confirmation');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) return unavailable(res, await currentClaim(req));
      if (error instanceof ApiError && error.status === 400) return renderForm(res, 'check-answers', data, { statementOfTruth: 'Your response could not be submitted. Check your answers and try again.' });
      throw error;
    }
  }
  router.get('/check-answers', (req, res) => checkAnswers(req, res, false));
  router.post('/check-answers', (req, res) => checkAnswers(req, res, true));
  router.get('/confirmation', (req, res) => {
    const claim = journey(req)!.confirmation;
    if (!claim) return res.redirect('/respond/claim');
    res.render('respond/confirmation.njk', { claim, responseLabels });
  });
  app.use('/respond', router);
}
