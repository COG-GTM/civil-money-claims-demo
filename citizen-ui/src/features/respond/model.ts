export type ResponseType = 'ADMIT_ALL' | 'ADMIT_PART' | 'REJECT';
export type PaymentOption = 'IMMEDIATELY' | 'BY_DATE' | 'INSTALMENTS';
export interface Party {
  name: string;
  address: { line1: string; line2?: string; city: string; postcode: string };
}
export interface DefendantResponse {
  type: ResponseType;
  admittedAmount?: number;
  reason?: string;
  paymentOption?: PaymentOption;
  paymentDate?: string;
  instalmentAmount?: number;
  statementOfTruth: boolean;
  submittedAt?: string;
}
export interface Claim {
  reference: string;
  claimantId?: string;
  state: string;
  claimant: Party;
  defendant: Party;
  principalAmount: number;
  interestAmount?: number;
  fee: number;
  totalAmount: number;
  amountBreakdown?: { reason: string; amount: number }[];
  reason?: string;
  timeline?: { date: string; description: string }[];
  createdAt: string;
  issuedAt?: string;
  responseDeadline?: string;
  response?: DefendantResponse;
  judgmentRequestedAt?: string;
  eligibleForDefaultJudgment: boolean;
  securityCode?: string;
}
export interface ClaimEvent { id: string; description: string; createdAt: string; type: string; }
export interface Answers {
  type?: ResponseType;
  admittedAmount?: string;
  reason?: string;
  paymentOption?: PaymentOption;
  paymentDay?: string;
  paymentMonth?: string;
  paymentYear?: string;
  instalmentAmount?: string;
}
export interface RespondSession {
  reference: string;
  answers: Answers;
  detailsComplete?: boolean;
  paymentComplete?: boolean;
  confirmation?: Claim;
}
export type FieldErrors = Record<string, string>;
export const referencePattern = /^000(MC|LC)[0-9]{3,}$/;
export const responseLabels: Record<ResponseType, string> = {
  ADMIT_ALL: 'I admit all of the claim', ADMIT_PART: 'I admit part of the claim', REJECT: 'I reject all of the claim'
};
export const paymentLabels: Record<PaymentOption, string> = {
  IMMEDIATELY: 'Immediately', BY_DATE: 'By a set date', INSTALMENTS: 'In monthly instalments'
};
export const textValue = (value: unknown): string => typeof value === 'string' ? value.trim() : '';
export const errorList = (errors: FieldErrors) => Object.entries(errors).map(([field, text]) => ({ text, href: `#${field}` }));

// Court deadlines expire after the entire London calendar day, including BST.
export function londonToday(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}
export function canRespond(claim: Claim): boolean {
  return ['ISSUED', 'AWAITING_RESPONSE', 'AWAITING_DEFAULT_JUDGMENT'].includes(claim.state)
    && !claim.response && !claim.judgmentRequestedAt;
}
export function canRequestJudgment(claim: Claim, now = new Date()): boolean {
  return claim.eligibleForDefaultJudgment === true && canRespond(claim)
    && !!claim.responseDeadline && claim.responseDeadline < londonToday(now);
}

// Parse pounds into integer pennies before validating limits; reject exponent notation and rounding.
export function pennies(value: string): number | undefined {
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(value)) return undefined;
  const [pounds, pence = ''] = value.split('.');
  return Number(pounds) * 100 + Number(pence.padEnd(2, '0'));
}
export function paymentDate(answers: Answers): string | undefined {
  const { paymentDay: day = '', paymentMonth: month = '', paymentYear: year = '' } = answers;
  if (!/^\d{1,2}$/.test(day) || !/^\d{1,2}$/.test(month) || !/^\d{4}$/.test(year)) return undefined;
  const date = `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
  const parsed = new Date(`${date}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date ? date : undefined;
}
export function validateDetails(answers: Answers, claim: Claim): FieldErrors {
  const errors: FieldErrors = {};
  if (answers.type === 'ADMIT_PART') {
    const amount = pennies(answers.admittedAmount || '');
    if (amount === undefined || amount <= 0 || amount >= Math.round(claim.totalAmount * 100)) {
      errors.admittedAmount = 'Enter an amount greater than £0 and less than the total claim, using no more than 2 decimal places';
    }
  }
  if (answers.type !== 'ADMIT_ALL') {
    if (!answers.reason) errors.reason = 'Enter why you disagree with the claim';
    else if (answers.reason.length > 5000) errors.reason = 'Your explanation must be 5,000 characters or fewer';
  }
  return errors;
}
export function validatePayment(answers: Answers, claim: Claim): FieldErrors {
  const errors: FieldErrors = {};
  if (!answers.paymentOption || !Object.hasOwn(paymentLabels, answers.paymentOption)) {
    errors.paymentOption = 'Select how you would like to pay';
  } else if (answers.paymentOption === 'BY_DATE') {
    const date = paymentDate(answers);
    if (!date || date < londonToday()) errors.paymentDay = 'Enter a real payment date that is today or in the future';
  } else if (answers.paymentOption === 'INSTALMENTS') {
    const amount = pennies(answers.instalmentAmount || '');
    const admitted = answers.type === 'ADMIT_ALL' ? Math.round(claim.totalAmount * 100) : pennies(answers.admittedAmount || '') || 0;
    if (amount === undefined || amount <= 0 || amount > admitted) errors.instalmentAmount = 'Enter a monthly amount greater than £0 and no more than the amount you admit';
  }
  return errors;
}
export function responsePayload(answers: Answers, claim: Claim): DefendantResponse {
  const response: DefendantResponse = { type: answers.type!, statementOfTruth: true };
  if (answers.type !== 'ADMIT_ALL') response.reason = answers.reason;
  if (answers.type !== 'REJECT') {
    response.admittedAmount = answers.type === 'ADMIT_ALL' ? claim.totalAmount : pennies(answers.admittedAmount!)! / 100;
    response.paymentOption = answers.paymentOption;
    if (answers.paymentOption === 'BY_DATE') response.paymentDate = paymentDate(answers);
    if (answers.paymentOption === 'INSTALMENTS') response.instalmentAmount = pennies(answers.instalmentAmount!)! / 100;
  }
  return response;
}
