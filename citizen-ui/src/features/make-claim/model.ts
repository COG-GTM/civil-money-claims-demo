import type { Request } from 'express';

export interface Party {
  type: 'INDIVIDUAL' | 'BUSINESS'; name: string; email?: string; phone?: string;
  address: { line1: string; line2: string; city: string; postcode: string; country: string };
}
export interface AmountItem { reason: string; amount: number; }
export interface Interest { claimed: boolean; rate: 8; startDate?: string; }
export interface TimelineEntry { date: string; description: string; }
export interface Evidence { id: string; fileName: string; contentType: string; sizeBytes: number; scanStatus: string; }
export interface Quote { principalAmount: number; interestAmount: number; dailyInterest: number; fee: number; totalAmount: number; calculationDate?: string; }
export interface Claim extends Quote { reference: string; state: string; serviceDate?: string; responseDeadline?: string; securityCode?: string; }
export interface FormError { field: string; text: string; }
export interface MakeClaimState {
  eligibility: Record<string, string>; claimant?: Party; defendant?: Party;
  amountBreakdown?: AmountItem[]; interest?: Interest; reason?: string; timeline?: TimelineEntry[];
  evidenceComplete?: boolean; documents: Evidence[]; reference?: string; quote?: Quote;
  statementOfTruth?: boolean; paymentReference?: string; issued?: Claim;
}
export const eligibilityQuestions = [
  { slug: '', field: 'over18', title: 'Are you 18 or over?', hint: 'You must be 18 or over to make a claim using this demo service.' },
  { slug: '/location', field: 'englandWales', title: 'Are you and the defendant in England or Wales?', hint: 'The defendant is the person or business you are claiming against. You need a postal address for them in England or Wales.' },
  { slug: '/amount', field: 'underLimit', title: 'Are you claiming £25,000 or less?', hint: 'This is the money you are owed, before interest and the claim fee. You must know the exact amount.' },
  { slug: '/parties', field: 'oneDefendant', title: 'Are you claiming against one person or business?', hint: 'This demo supports one claimant and one defendant. More complex claims are not supported.' }
] as const;
export const tasks = [
  { key: 'claimant', path: 'your-details', title: 'Your details', section: '1. Tell us about the people involved' },
  { key: 'defendant', path: 'defendant-details', title: 'Defendant details', section: '1. Tell us about the people involved' },
  { key: 'amountBreakdown', path: 'amount', title: 'Claim amount', section: '2. Tell us about your claim' },
  { key: 'interest', path: 'interest', title: 'Interest', section: '2. Tell us about your claim' },
  { key: 'reason', path: 'reason', title: 'Why you are making the claim', section: '2. Tell us about your claim' },
  { key: 'timeline', path: 'timeline', title: 'Timeline of events', section: '2. Tell us about your claim' },
  { key: 'evidenceComplete', path: 'evidence', title: 'Upload evidence', section: '3. Prepare your evidence' }
] as const;

export function state(req: Request): MakeClaimState {
  return (req.session.makeClaim ||= { eligibility: {}, documents: [] }) as MakeClaimState;
}
export function eligible(s: MakeClaimState): boolean { return eligibilityQuestions.every(q => s.eligibility[q.field] === 'yes'); }
export function complete(s: MakeClaimState): boolean { return tasks.every(task => Boolean(s[task.key])); }
export function changed(s: MakeClaimState): void { delete s.quote; delete s.statementOfTruth; }
export function claimInput(s: MakeClaimState, claimantId = 'demo-claimant') {
  return { claimantId, claimant: s.claimant, defendant: s.defendant, amountBreakdown: s.amountBreakdown,
    interest: s.interest, reason: s.reason, timeline: s.timeline };
}
export function text(value: unknown): string { return typeof value === 'string' ? value.trim() : ''; }
export function today(): string { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date()); }
export function dateParts(date?: string) {
  const [year = '', month = '', day = ''] = (date || '').split('-');
  return { day, month, year };
}
export function readDate(body: Record<string, unknown>): string | undefined {
  const day = text(body.day), month = text(body.month), year = text(body.year);
  if (!/^\d{1,2}$/.test(day) || !/^\d{1,2}$/.test(month) || !/^\d{4}$/.test(year) || Number(year) < 1900) return;
  const value = `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
  const date = new Date(`${value}T12:00:00Z`);
  return !Number.isNaN(date.valueOf()) && date.toISOString().slice(0, 10) === value ? value : undefined;
}

export function validateParty(body: Record<string, unknown>, claimant: boolean): { value: Party; errors: FormError[] } {
  const errors: FormError[] = [];
  const values = Object.fromEntries(['type', 'name', 'email', 'phone', 'line1', 'line2', 'city', 'postcode', 'country'].map(key => [key, text(body[key])]));
  for (const [key, label, max] of [['name', 'a full name or business name', 200], ['line1', 'an address', 200], ['city', 'a town or city', 100]] as const) {
    if (!values[key]) errors.push({ field: key, text: `Enter ${label}` });
    else if (values[key].length > max) errors.push({ field: key, text: `Enter ${label} using ${max} characters or fewer` });
  }
  if (!['INDIVIDUAL', 'BUSINESS'].includes(values.type)) errors.push({ field: 'type', text: 'Select a person or a business' });
  if ((claimant && !values.email) || (values.email && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email) || values.email.length > 254))) errors.push({ field: 'email', text: 'Enter an email address in the correct format, like name@example.com' });
  if (values.phone && (!/^[+\d()\s-]{7,25}$/.test(values.phone))) errors.push({ field: 'phone', text: 'Enter a telephone number in the correct format' });
  if (values.line2.length > 200) errors.push({ field: 'line2', text: 'Address line 2 must be 200 characters or fewer' });
  const compact = values.postcode.replace(/\s/g, '').toUpperCase();
  if (!/^[A-Z]{1,2}\d[A-Z\d]?\d[A-Z]{2}$/.test(compact)) errors.push({ field: 'postcode', text: 'Enter a full UK postcode' });
  if (!['England', 'Wales'].includes(values.country)) errors.push({ field: 'country', text: 'Select England or Wales' });
  return { errors, value: { type: values.type as Party['type'], name: values.name, email: values.email, phone: values.phone,
    address: { line1: values.line1, line2: values.line2, city: values.city, postcode: compact.length > 3 ? `${compact.slice(0, -3)} ${compact.slice(-3)}` : compact, country: values.country } } };
}

// Integer pence keep item totals exact before sending GBP numbers to the API calculator.
export function validateAmounts(rows: Record<string, unknown>[]): { value: AmountItem[]; errors: FormError[] } {
  const errors: FormError[] = []; let total = 0;
  const value = rows.map((row, i) => {
    const reason = text(row.reason), amount = text(row.amount);
    if (!reason || reason.length > 500) errors.push({ field: `item-${i}-reason`, text: `Enter a description for item ${i + 1} using 500 characters or fewer` });
    const [pounds, pennies = ''] = amount.split('.');
    const pence = Number(pounds) * 100 + Number(pennies.padEnd(2, '0'));
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(amount) || pence <= 0 || pence > 2500000) errors.push({ field: `item-${i}-amount`, text: `Enter an amount for item ${i + 1} between £0.01 and £25,000, with no more than 2 decimal places` });
    else total += pence;
    return { reason, amount: pence / 100 };
  });
  if (total > 2500000) errors.push({ field: 'item-0-amount', text: 'The total claim amount must be £25,000 or less, before interest and fees' });
  return { errors, value };
}
