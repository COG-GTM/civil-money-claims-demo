import type { ClaimSummary } from '../../lib/api-client';

export const states = ['DRAFT', 'ISSUED', 'AWAITING_RESPONSE', 'RESPONDED', 'AWAITING_DEFAULT_JUDGMENT', 'SETTLED', 'TRANSFERRED'] as const;
export const tabs = ['summary', 'parties', 'claim', 'documents', 'history'] as const;
export type Tab = typeof tabs[number];
export interface Party {
  type: 'INDIVIDUAL' | 'BUSINESS'; name: string; email?: string; phone?: string;
  address?: { line1: string; line2?: string; city: string; postcode: string; country?: string };
}
export interface Claim extends ClaimSummary {
  state: typeof states[number]; claimant: Party; defendant: Party;
  principalAmount: number; interestAmount?: number; dailyInterest?: number; fee: number; totalAmount: number;
  amountBreakdown?: { reason: string; amount: number }[];
  interest?: { claimed: boolean; rate?: number; startDate?: string };
  reason?: string; timeline?: { date: string; description: string }[];
  issuedAt?: string; serviceDate?: string; responseDeadline?: string; acknowledged?: boolean;
  judgmentRequestedAt?: string;
  response?: { type: string; admittedAmount?: number; reason?: string; paymentOption?: string; paymentDate?: string; instalmentAmount?: number; submittedAt?: string };
}
export interface Document {
  id: string; fileName: string; contentType: string; sizeBytes: number;
  scanStatus: 'CLEAN' | 'PENDING' | 'REJECTED'; createdAt: string;
}
export interface CaseNote { id: string; text: string; author: string; createdAt: string; }
export interface ClaimEvent { id: string; type: string; description: string; actor?: string; createdAt: string; }

export function dateTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', { dateStyle: 'long', timeStyle: 'short', timeZone: 'Europe/London' }).format(new Date(value));
}
