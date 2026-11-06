import type { Express, Request, Response } from 'express';
import { ApiError, apiContext, apiForReference, getCombinedClaims } from '../../lib/api-client';
import { requireCaseworker } from '../sign-in/access';
import { dateTime, states, tabs, type Claim, type Document, type CaseNote, type ClaimEvent, type Tab } from './models';

const referencePattern = /^000(?:MC|LC)\d{3,}$/;
const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const pageSize = 20;
const text = (value: unknown): string => typeof value === 'string' ? value.trim() : '';
const label = (value: string): string => value.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());

function validDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && Number(value.slice(0, 4)) >= 1900
    && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
}

function listUrl(filters: Record<string, string>, page = 0): string {
  const query = new URLSearchParams(Object.entries(filters).filter(([, value]) => value));
  if (page) query.set('page', String(page));
  return `/caseworker${query.size ? `?${query}` : ''}`;
}

async function casePage(req: Request, res: Response, tab: Tab, extra: Record<string, unknown> = {}, status = 200): Promise<void> {
  const reference = text(req.params.reference).toUpperCase();
  if (!referencePattern.test(reference)) { res.status(404).render('errors/not-found.njk'); return; }
  const api = apiForReference(reference), context = apiContext(req);
  try {
    const claim = await api.get<Claim>(`/api/claims/${reference}`, context);
    const documents = tab === 'documents' ? await api.get<Document[]>(`/api/claims/${reference}/documents`, context) : [];
    const [events, notes] = tab === 'history' ? await Promise.all([
      api.get<ClaimEvent[]>(`/api/claims/${reference}/events`, context),
      api.get<CaseNote[]>(`/api/claims/${reference}/notes`, context)
    ]) : [[], []];
    res.status(status).render('caseworker/detail.njk', {
      pageTitle: `${label(tab)} – ${reference}`, claim, reference, tab,
      tabLinks: tabs.map(name => ({ name, label: label(name), href: `/caseworker/${reference}/${name}` })),
      legacy: reference.startsWith('000LC'),
      documents: documents.map(doc => ({ ...doc, sizeLabel: doc.sizeBytes < 1024 ? `${doc.sizeBytes} bytes` : `${(doc.sizeBytes / 1024).toFixed(1)} KB` })),
      events: events.sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(event => ({ ...event, dateLabel: dateTime(event.createdAt) })),
      notes: notes.sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(note => ({ ...note, dateLabel: dateTime(note.createdAt) })),
      backLink: '/caseworker', ...extra
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) { res.status(404).render('errors/not-found.njk'); return; }
    throw error;
  }
}

export function registerRoutes(app: Express): void {
  app.use('/caseworker', requireCaseworker);
  app.get('/caseworker', async (req, res) => {
    const filters = { state: text(req.query.state), from: text(req.query.from), to: text(req.query.to) };
    const errors: { text: string; href: string }[] = [];
    if (filters.state && !states.includes(filters.state as typeof states[number])) errors.push({ text: 'Select a valid case state', href: '#state' });
    for (const field of ['from', 'to'] as const) {
      if (filters[field] && !validDate(filters[field])) errors.push({ text: `Enter a valid ${field === 'from' ? 'from' : 'to'} date`, href: `#${field}` });
    }
    if (!errors.length && filters.from && filters.to && filters.from > filters.to) errors.push({ text: 'The to date must be on or after the from date', href: '#to' });
    // The shared combined-list client requests up to 500 rows per API, sufficient for the 200-case seed.
    const pageValue = text(req.query.page) || '0';
    if (!/^\d{1,2}$/.test(pageValue) || Number(pageValue) > 24) errors.push({ text: 'Select a page between 1 and 25', href: '#case-list' });
    const stateItems = [{ value: '', text: 'All states', selected: !filters.state }, ...states.map(state => ({ value: state, text: label(state), selected: filters.state === state }))];
    const view = { pageTitle: 'Manage cases', filters, stateItems, errors,
      fieldErrors: Object.fromEntries(errors.map(error => [error.href.slice(1), { text: error.text }])) };
    if (errors.length) { res.status(400).render('caseworker/list.njk', { ...view, pageTitle: 'Error: Manage cases' }); return; }
    const page = Number(pageValue);
    const query = Object.fromEntries(Object.entries(filters).filter(([, value]) => value));
    const result = await getCombinedClaims({ ...query, page, size: pageSize }, apiContext(req));
    const lastPage = Math.max(0, Math.ceil(result.total / pageSize) - 1);
    if (page > lastPage) { res.redirect(303, listUrl(filters, Math.min(lastPage, 24))); return; }
    res.render('caseworker/list.njk', { ...view, claims: result.items, total: result.total,
      start: result.total ? page * pageSize + 1 : 0, end: page * pageSize + result.items.length,
      pageNumber: page + 1, pages: lastPage + 1,
      previous: page > 0 ? listUrl(filters, page - 1) : undefined,
      next: page < lastPage && page < 24 ? listUrl(filters, page + 1) : undefined });
  });

  app.get('/caseworker/:reference/documents/:documentId', async (req, res) => {
    const reference = text(req.params.reference).toUpperCase(), id = text(req.params.documentId);
    if (!referencePattern.test(reference) || !uuidPattern.test(id)) { res.status(404).render('errors/not-found.njk'); return; }
    try {
      const download = await apiForReference(reference).download(`/api/claims/${reference}/documents/${id}`, apiContext(req));
      const type = download.headers.get('content-type')?.split(';')[0];
      res.set('Content-Type', type && ['application/pdf', 'image/jpeg', 'image/png'].includes(type) ? type : 'application/octet-stream');
      res.set('Content-Disposition', download.headers.get('content-disposition') || 'attachment');
      res.send(Buffer.from(await download.arrayBuffer()));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) { res.status(404).render('errors/not-found.njk'); return; }
      throw error;
    }
  });

  app.post('/caseworker/:reference/notes', async (req, res) => {
    const reference = text(req.params.reference).toUpperCase();
    if (!referencePattern.test(reference)) { res.status(404).render('errors/not-found.njk'); return; }
    const note = text(req.body?.text);
    const noteError = !note ? 'Enter a case note' : note.length > 10000 ? 'Case note must be 10,000 characters or fewer' : undefined;
    if (noteError) {
      await casePage(req, res, 'history', { note, noteError, errors: [{ text: noteError, href: '#note' }], pageTitle: `Error: Add a case note – ${reference}` }, 400);
      return;
    }
    await apiForReference(reference).post(`/api/claims/${reference}/notes`, { text: note, author: req.session.user!.id }, apiContext(req));
    req.session.caseworkerNoteSaved = reference;
    res.redirect(303, `/caseworker/${reference}/history`);
  });

  app.get('/caseworker/:reference', (req, res) => casePage(req, res, 'summary'));
  app.get('/caseworker/:reference/:tab', (req, res) => {
    const tab = text(req.params.tab) as Tab;
    if (!tabs.includes(tab)) { res.status(404).render('errors/not-found.njk'); return; }
    const noteSaved = tab === 'history' && req.session.caseworkerNoteSaved === text(req.params.reference).toUpperCase();
    if (noteSaved) delete req.session.caseworkerNoteSaved;
    return casePage(req, res, tab, { noteSaved });
  });
}
