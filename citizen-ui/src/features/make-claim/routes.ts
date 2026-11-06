import type { Express, Request, Response, NextFunction } from 'express';
import multer from 'multer';
import { randomUUID } from 'node:crypto';
import { civilApi, apiContext, ApiError } from '../../lib/api-client';
import { state, eligible, complete, changed, claimInput, text, today, dateParts, readDate, validateParty, validateAmounts,
  eligibilityQuestions, tasks, type MakeClaimState, type FormError, type Quote, type Claim, type Evidence, type TimelineEntry } from './model';

const root = '/make-claim';
const maxFileSize = 10 * 1024 * 1024;
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: maxFileSize, files: 1, fields: 2 },
  fileFilter: (_req, file, callback) => {
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.mimetype)) return callback(new Error('FILE_TYPE'));
    callback(null, true);
  } }).single('file');

function render(res: Response, page: string, data: Record<string, unknown> = {}, errors: FormError[] = [], status = 400): void {
  res.status(errors.length ? status : 200).render(`make-claim/${page}.njk`, {
    backLink: `${root}/task-list`, ...data,
    errors: errors.map(error => ({ text: error.text, href: `#${error.field}` })),
    fieldErrors: Object.fromEntries(errors.map(error => [error.field, { text: error.text }]))
  });
}
async function saveDraft(req: Request, next: MakeClaimState): Promise<void> {
  const body = claimInput(next, req.session.user?.role === 'claimant' ? req.session.user.id : 'demo-claimant');
  const claim = next.reference
    ? await civilApi.put<Claim>(`/api/claims/${next.reference}`, body, apiContext(req))
    : await civilApi.post<Claim>('/api/claims', body, apiContext(req));
  next.reference = claim.reference;
  changed(next);
  req.session.makeClaim = next;
}
async function quote(req: Request): Promise<Quote> {
  const s = state(req);
  s.quote = await civilApi.post<Quote>('/api/fees/quote', {
    amountBreakdown: s.amountBreakdown, interest: s.interest, calculationDate: today()
  }, apiContext(req));
  return s.quote;
}
function rows(value: unknown, max: number): Record<string, unknown>[] {
  return Array.isArray(value) && value.length > 0
    ? value.slice(0, max).map(row => row && typeof row === 'object' ? row : {}) : [{}];
}
function editing(req: Request): boolean { return req.query.change === 'true'; }
function nextPage(req: Request): string { return editing(req) ? `${root}/check-answers` : `${root}/task-list`; }
function requireComplete(req: Request, res: Response): boolean {
  if (!complete(state(req))) { res.redirect(`${root}/task-list`); return false; }
  return true;
}

export function registerRoutes(app: Express): void {
  app.use(root, (req, res, next) => {
    res.set('Cache-Control', 'no-store');
    const s = state(req);
    if (s.issued && !['/confirmation', '/new'].includes(req.path)) return res.redirect(`${root}/confirmation`);
    if (!eligible(s) && !req.path.startsWith('/eligibility') && !['/not-eligible', '/new'].includes(req.path)) return res.redirect(`${root}/eligibility`);
    next();
  });

  eligibilityQuestions.forEach((question, index) => {
    const url = `${root}/eligibility${question.slug}`;
    app.get(url, (req, res) => {
      const missing = eligibilityQuestions.slice(0, index).find(q => state(req).eligibility[q.field] !== 'yes');
      if (missing) return res.redirect(`${root}/eligibility${missing.slug}`);
      render(res, 'eligibility', { question, value: state(req).eligibility[question.field], step: index + 1,
        backLink: index ? `${root}/eligibility${eligibilityQuestions[index - 1].slug}` : '/' });
    });
    app.post(url, async (req, res) => {
      const value = text(req.body[question.field]);
      if (!['yes', 'no'].includes(value)) return render(res, 'eligibility', { question, value, step: index + 1,
        backLink: index ? `${root}/eligibility${eligibilityQuestions[index - 1].slug}` : '/' }, [{ field: question.field, text: 'Select yes or no' }]);
      const s = state(req);
      s.eligibility[question.field] = value;
      if (value === 'no') return res.redirect(`${root}/not-eligible`);
      if (index < eligibilityQuestions.length - 1) return res.redirect(`${root}/eligibility${eligibilityQuestions[index + 1].slug}`);
      if (!eligible(s)) return res.redirect(`${root}/eligibility`);
      if (!s.reference) await saveDraft(req, { ...s });
      res.redirect(`${root}/task-list`);
    });
  });
  app.get(`${root}/not-eligible`, (_req, res) => render(res, 'not-eligible', { backLink: `${root}/eligibility` }));
  app.post(`${root}/new`, (req, res) => { delete req.session.makeClaim; res.redirect(`${root}/eligibility`); });

  app.get(`${root}/task-list`, (req, res) => {
    const s = state(req);
    const groups = [...new Set(tasks.map(task => task.section))].map(title => ({ title, items: tasks.filter(t => t.section === title).map(t => ({
      title: { text: t.title }, href: `${root}/${t.path}`, status: s[t.key] ? { text: 'Completed' } : { tag: { text: 'Incomplete', classes: 'govuk-tag--blue' } }
    })) }));
    render(res, 'task-list', { groups, completed: tasks.filter(t => Boolean(s[t.key])).length, allComplete: complete(s), backLink: `${root}/eligibility/parties` });
  });

  for (const [path, key] of [['your-details', 'claimant'], ['defendant-details', 'defendant']] as const) {
    app.get(`${root}/${path}`, (req, res) => {
      const party = state(req)[key];
      render(res, 'party', { claimant: key === 'claimant', values: party ? { ...party, ...party.address } : {}, change: editing(req) });
    });
    app.post(`${root}/${path}`, async (req, res) => {
      const { value, errors } = validateParty(req.body, key === 'claimant');
      if (errors.length) return render(res, 'party', { claimant: key === 'claimant', values: req.body, change: editing(req) }, errors);
      await saveDraft(req, { ...state(req), [key]: value });
      res.redirect(nextPage(req));
    });
  }

  app.get(`${root}/amount`, (req, res) => render(res, 'amount', { items: state(req).amountBreakdown || [{}], change: editing(req) }));
  app.post(`${root}/amount`, async (req, res) => {
    const items = rows(req.body.items, 10);
    const action = text(req.body.action);
    if (action === 'add' && items.length < 10) return render(res, 'amount', { items: [...items, {}], change: editing(req) });
    if (/^remove-\d+$/.test(action) && items.length > 1) return render(res, 'amount', { items: items.filter((_row, i) => i !== Number(action.slice(7))), change: editing(req) });
    const { value, errors } = validateAmounts(items);
    if (Array.isArray(req.body.items) && req.body.items.length > 10) errors.push({ field: 'item-0-reason', text: 'Enter no more than 10 items' });
    if (errors.length) return render(res, 'amount', { items, change: editing(req) }, errors);
    await saveDraft(req, { ...state(req), amountBreakdown: value });
    res.redirect(nextPage(req));
  });

  app.get(`${root}/interest`, (req, res) => {
    const interest = state(req).interest;
    render(res, 'interest', { values: { claimed: interest ? interest.claimed ? 'yes' : 'no' : '', ...dateParts(interest?.startDate) }, change: editing(req) });
  });
  app.post(`${root}/interest`, async (req, res) => {
    const claimed = text(req.body.claimed), startDate = readDate(req.body), errors: FormError[] = [];
    if (!['yes', 'no'].includes(claimed)) errors.push({ field: 'claimed', text: 'Select whether you want to claim interest' });
    if (claimed === 'yes' && (!startDate || startDate > today())) errors.push({ field: 'interest-date-day', text: 'Enter a real interest start date that is today or in the past' });
    if (errors.length) return render(res, 'interest', { values: req.body, change: editing(req) }, errors);
    await saveDraft(req, { ...state(req), interest: { claimed: claimed === 'yes', rate: 8, ...(claimed === 'yes' ? { startDate } : {}) } });
    res.redirect(nextPage(req));
  });

  app.get(`${root}/reason`, (req, res) => render(res, 'reason', { reason: state(req).reason, change: editing(req) }));
  app.post(`${root}/reason`, async (req, res) => {
    const reason = text(req.body.reason);
    if (!reason || reason.length > 10000) return render(res, 'reason', { reason, change: editing(req) }, [{ field: 'reason', text: 'Explain why you are making the claim using 10,000 characters or fewer' }]);
    await saveDraft(req, { ...state(req), reason });
    res.redirect(nextPage(req));
  });

  app.get(`${root}/timeline`, (req, res) => render(res, 'timeline', { events: state(req).timeline?.map(event => ({ ...dateParts(event.date), description: event.description })) || [{}], change: editing(req) }));
  app.post(`${root}/timeline`, async (req, res) => {
    const events = rows(req.body.events, 20), action = text(req.body.action), errors: FormError[] = [], value: TimelineEntry[] = [];
    if (action === 'add' && events.length < 20) return render(res, 'timeline', { events: [...events, {}], change: editing(req) });
    if (/^remove-\d+$/.test(action) && events.length > 1) return render(res, 'timeline', { events: events.filter((_row, i) => i !== Number(action.slice(7))), change: editing(req) });
    events.forEach((event, i) => {
      const date = readDate(event), description = text(event.description);
      if (!date || date > today()) errors.push({ field: `event-${i}-day`, text: `Enter a real date for event ${i + 1} that is today or in the past` });
      if (!description || description.length > 2000) errors.push({ field: `event-${i}-description`, text: `Describe event ${i + 1} using 2,000 characters or fewer` });
      if (date) value.push({ date, description });
    });
    if (Array.isArray(req.body.events) && req.body.events.length > 20) errors.push({ field: 'event-0-day', text: 'Enter no more than 20 events' });
    if (errors.length) return render(res, 'timeline', { events, change: editing(req) }, errors);
    await saveDraft(req, { ...state(req), timeline: value.sort((a, b) => a.date.localeCompare(b.date)) });
    res.redirect(nextPage(req));
  });

  app.get(`${root}/evidence`, async (req, res) => {
    const s = state(req);
    if (!s.reference) return res.redirect(`${root}/eligibility/parties`);
    s.documents = await civilApi.get<Evidence[]>(`/api/claims/${s.reference}/documents`, apiContext(req));
    render(res, 'evidence', { documents: s.documents, change: editing(req), uploaded: req.query.uploaded === 'true' });
  });
  // CSRF is checked by the shared middleware before Multer allocates an upload buffer.
  app.post(`${root}/evidence/upload`, (req: Request, res: Response, next: NextFunction) => {
    upload(req, res, error => {
      if (!error) return next();
      const tooLarge = error instanceof multer.MulterError && error.code === 'LIMIT_FILE_SIZE';
      render(res, 'evidence', { documents: state(req).documents, change: editing(req) }, [{ field: 'file', text: tooLarge ? 'The selected file must be smaller than 10 MB' : 'Upload one PDF, JPG or PNG file' }], tooLarge ? 413 : 400);
    });
  }, async (req, res) => {
    const s = state(req), file = req.file;
    const showError = (message: string, status = 400) => render(res, 'evidence', { documents: s.documents, change: editing(req) }, [{ field: 'file', text: message }], status);
    if (!file || file.size === 0) return showError('Select a PDF, JPG or PNG file to upload');
    const valid = file.mimetype === 'application/pdf' ? file.buffer.subarray(0, 5).toString() === '%PDF-'
      : file.mimetype === 'image/jpeg' ? file.buffer.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))
        : file.buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    if (!valid) return showError('The selected file does not match its format. Upload a valid PDF, JPG or PNG', 415);
    if (!s.reference) return res.redirect(`${root}/eligibility/parties`);
    try {
      const document = await civilApi.upload<Evidence>(`/api/claims/${s.reference}/documents`, new Blob([new Uint8Array(file.buffer)], { type: file.mimetype }), file.originalname, apiContext(req));
      s.documents.push(document); changed(s); s.evidenceComplete = false;
      res.json({ redirect: `${root}/evidence?uploaded=true${editing(req) ? '&change=true' : ''}` });
    } catch (error) {
      if (error instanceof ApiError && [400, 413, 415].includes(error.status)) return showError(error.status === 413 ? 'The selected file must be smaller than 10 MB' : 'The claims service could not accept this file. Upload a valid PDF, JPG or PNG', error.status);
      throw error;
    } finally { delete req.file; }
  });
  app.post(`${root}/evidence/remove`, async (req, res) => {
    const s = state(req), id = text(req.body.documentId);
    if (!s.documents.some(document => document.id === id)) return res.status(404).render('errors/not-found.njk');
    await civilApi.delete(`/api/claims/${s.reference}/documents/${encodeURIComponent(id)}`, apiContext(req));
    s.documents = s.documents.filter(document => document.id !== id); changed(s); s.evidenceComplete = false;
    res.redirect(`${root}/evidence${editing(req) ? '?change=true' : ''}`);
  });
  app.post(`${root}/evidence`, (req, res) => { state(req).evidenceComplete = true; res.redirect(nextPage(req)); });

  app.get(`${root}/fee`, async (req, res) => {
    if (!requireComplete(req, res)) return;
    render(res, 'fee', { quote: await quote(req), interest: state(req).interest });
  });
  app.get(`${root}/check-answers`, async (req, res) => {
    if (!requireComplete(req, res)) return;
    render(res, 'check-answers', { claim: state(req), quote: await quote(req), backLink: `${root}/fee` });
  });
  app.post(`${root}/check-answers`, async (req, res) => {
    if (!requireComplete(req, res)) return;
    const s = state(req);
    if (req.body.statementOfTruth !== 'yes') return render(res, 'check-answers', { claim: s, quote: s.quote || await quote(req), backLink: `${root}/fee` }, [{ field: 'statementOfTruth', text: 'Confirm that you believe the facts stated in this claim are true' }]);
    await saveDraft(req, { ...s });
    state(req).statementOfTruth = true;
    state(req).paymentReference ||= `DEMO-PAY-${randomUUID()}`;
    await quote(req);
    res.redirect(`${root}/payment`);
  });
  app.get(`${root}/payment`, (req, res) => {
    const s = state(req);
    if (!complete(s) || !s.statementOfTruth || !s.quote) return res.redirect(`${root}/check-answers`);
    render(res, 'payment', { quote: s.quote, backLink: `${root}/check-answers` });
  });
  app.post(`${root}/payment`, async (req, res) => {
    const s = state(req), errors: FormError[] = [];
    // Accept only the published test card, and discard every card field before any API call.
    const cardNumber = text(req.body.cardNumber).replace(/\s/g, '');
    if (cardNumber !== '4242424242424242') errors.push({ field: 'cardNumber', text: 'Use the demo card number 4242 4242 4242 4242. Do not enter real card details' });
    if (!text(req.body.cardholderName) || text(req.body.cardholderName).length > 100) errors.push({ field: 'cardholderName', text: 'Enter a demo name on the card' });
    const month = text(req.body.expiryMonth), year = text(req.body.expiryYear);
    const expiry = `${year.length === 2 ? `20${year}` : year}-${month.padStart(2, '0')}`;
    if (!/^\d{1,2}$/.test(month) || Number(month) < 1 || Number(month) > 12 || !/^\d{2}(\d{2})?$/.test(year) || expiry < today().slice(0, 7)) errors.push({ field: 'expiryMonth', text: 'Enter an expiry date that is this month or in the future' });
    if (text(req.body.securityCode) !== '123') errors.push({ field: 'securityCode', text: 'Use the demo card security code 123' });
    for (const field of ['cardNumber', 'cardholderName', 'expiryMonth', 'expiryYear', 'securityCode']) delete req.body[field];
    if (!complete(s) || !s.statementOfTruth || !s.quote || !s.reference) return res.redirect(`${root}/check-answers`);
    if (errors.length) return render(res, 'payment', { quote: s.quote, backLink: `${root}/check-answers` }, errors);
    s.issued = await civilApi.post<Claim>(`/api/claims/${s.reference}/issue`, { paymentReference: s.paymentReference, statementOfTruth: true }, apiContext(req));
    res.redirect(`${root}/confirmation`);
  });
  app.get(`${root}/confirmation`, (req, res) => {
    if (!state(req).issued) return res.redirect(`${root}/task-list`);
    render(res, 'confirmation', { claim: state(req).issued, backLink: null });
  });
}
