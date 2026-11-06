import { describe, expect, it } from 'vitest';
import { canRequestJudgment, londonToday, paymentDate, pennies, validatePayment, type Claim } from './model';

const eligible = { state: 'AWAITING_RESPONSE', responseDeadline: '2026-07-01', eligibleForDefaultJudgment: true } as Claim;
describe('court calendar and response validation', () => {
  it('does not offer judgment before the end of the London deadline day', () => {
    expect(canRequestJudgment(eligible, new Date('2026-07-01T22:59:59Z'))).toBe(false);
    expect(canRequestJudgment(eligible, new Date('2026-07-01T23:00:00Z'))).toBe(true);
    expect(londonToday(new Date('2026-07-01T23:30:00Z'))).toBe('2026-07-02');
  });
  it.each([
    { eligibleForDefaultJudgment: false }, { responseDeadline: undefined }, { state: 'DRAFT' },
    { state: 'RESPONDED' }, { state: 'SETTLED' }, { state: 'TRANSFERRED' },
    { response: { type: 'REJECT', statementOfTruth: true } }, { judgmentRequestedAt: '2026-07-01T12:00:00Z' }
  ])('does not offer judgment for an ineligible claim %j', override => {
    expect(canRequestJudgment({ ...eligible, ...override } as Claim, new Date('2026-07-03T12:00:00Z'))).toBe(false);
  });
  it.each(['0.001', '1e2', '-1', 'NaN', '1,000', '', '100000000000000000'])('rejects invalid currency %s', amount => {
    expect(pennies(amount)).toBeUndefined();
  });
  it('uses integer pennies and real calendar dates', () => {
    expect(pennies('12.34')).toBe(1234);
    expect(pennies('12.3')).toBe(1230);
    expect(paymentDate({ paymentYear: '2028', paymentMonth: '2', paymentDay: '29' })).toBe('2028-02-29');
    expect(paymentDate({ paymentYear: '2027', paymentMonth: '2', paymentDay: '29' })).toBeUndefined();
    expect(validatePayment({ type: 'ADMIT_ALL', paymentOption: 'BY_DATE', paymentYear: '2001', paymentMonth: '1', paymentDay: '1' }, eligible)).toHaveProperty('paymentDay');
  });
});
