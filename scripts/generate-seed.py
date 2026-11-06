#!/usr/bin/env python3
"""Reproducible synthetic fixtures; generated SQL evaluates relative dates on reset."""
import json
from pathlib import Path
import random
import struct
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = uuid.UUID('0dbe7634-1010-4772-9849-06e7bf41ec9f')
STATES = ['DRAFT', 'ISSUED', 'AWAITING_RESPONSE', 'RESPONDED',
          'AWAITING_DEFAULT_JUDGMENT', 'SETTLED', 'TRANSFERRED']
NAMES = ['Amelia Clarke', 'Oliver Hughes', 'Priya Shah', 'Mohammed Ali', 'Sophie Evans',
         'Thomas Williams', 'Grace Davies', 'Noah Wilson', 'Aisha Khan', 'George Taylor',
         'Isla Brown', 'Henry Martin', 'Olivia Roberts', 'Daniel Patel', 'Emily Jones',
         'Lucas Thompson', 'Freya Lewis', 'Arthur Walker', 'Charlotte Green', 'Jacob Hall']
TOWNS = [('Bristol', 'BS1 4ST', 'England'), ('Cardiff', 'CF10 1EP', 'Wales'),
         ('Leeds', 'LS1 2HD', 'England'), ('Manchester', 'M1 1AD', 'England'),
         ('Nottingham', 'NG1 2AS', 'England'), ('Swansea', 'SA1 3QW', 'Wales'),
         ('London', 'SE1 7PB', 'England'), ('Birmingham', 'B1 1BB', 'England')]
STREETS = ['Station Road', 'Cedar Close', 'Mill Lane', 'Park Avenue', 'Orchard Way', 'High Street']
REASONS = [('Unpaid invoice', 'I completed the agreed decorating work. The invoice remains unpaid despite two written reminders.'),
           ('Deposit refund', 'The booking was cancelled within the agreed cancellation period. The deposit has not been returned.'),
           ('Repair costs', 'The supplied appliance was faulty. I paid for the necessary repair after asking the supplier to resolve it.'),
           ('Loan repayment', 'I lent the defendant money under a written repayment agreement. The agreed repayment date has passed.'),
           ('Undelivered goods', 'I paid for garden furniture which was not delivered. The promised refund has not arrived.'),
           ('Building work', 'The agreed building work was incomplete. I paid another contractor to complete the remaining work.'),
           ('Professional services', 'I delivered the agreed design services and the defendant has not paid the final invoice.'),
           ('Rental equipment', 'The equipment was returned on time but the agreed refundable deposit has not been repaid.')]


def uid(key):
    return str(uuid.uuid5(NAMESPACE, key))


def party(i, defendant=False):
    city, postcode, country = TOWNS[i % len(TOWNS)]
    business = defendant and i % 4 == 0
    return {'type': 'BUSINESS' if business else 'INDIVIDUAL',
            'name': f'{["Cedar", "Orchard", "Meadow", "Riverside"][i % 4]} Workshop Demo Ltd' if business else NAMES[i % len(NAMES)],
            'email': f'synthetic-{i}@example.test', 'phone': f'07700 900{i % 1000:03}',
            'address': {'line1': f'{10 + i % 90} {STREETS[i % len(STREETS)]}', 'line2': '',
                        'city': city, 'postcode': postcode, 'country': country}}


def claims(kind, count):
    rng = random.Random(20261106 + count)
    rows = []
    for i in range(1, count + 1):
        reference = f'000{kind}{i:03}'
        state = STATES[(i - 3) % len(STATES)]
        if i == 1: state = 'AWAITING_RESPONSE'
        if i == 2: state = 'AWAITING_DEFAULT_JUDGMENT'
        acknowledged = i % 11 == 0 and state != 'DRAFT'
        issue_offset = -2 if state in ('ISSUED', 'AWAITING_RESPONSE', 'DRAFT') else -60 - i % 90
        label, reason = REASONS[(i - 1) % len(REASONS)]
        principal = [50, 25000, 300, 500, 1000, 1500, 3000, 5000, 10000][i - 1] if i <= 9 else rng.randint(5000, 2500000) / 100
        row = {'id': uid(reference), 'reference': reference, 'state': state,
               'claimantId': 'demo-claimant', 'claimant': party(i - 1), 'defendant': party(i + 7, True),
               'amountBreakdown': [{'reason': label, 'amount': principal}], 'principalAmount': principal,
               'reason': reason, 'securityCode': 'DEMO2026' if i == 1 else f'{kind}{i:06}',
               'acknowledged': acknowledged, 'interestClaimed': i % 3 == 0,
               'issueOffset': issue_offset, 'issueFixed': None, 'version': 0, 'index': i}
        # Closed historical examples keep the live answerable claims usable all year.
        edge = {count - 3: '2026-03-15', count - 2: '2025-12-06',
                count - 1: '2026-03-10', count: '2026-10-06'}
        if i in edge:
            row.update(issueFixed=edge[i], state='TRANSFERRED', acknowledged=False)
        rows.append(row)
    return rows


def pdf():
    # Minimal valid PDF with an xref table; intentionally no real personal data.
    text = 'BT /F1 18 Tf 50 780 Td (Synthetic demo invoice) Tj 0 -30 Td /F1 12 Tf '
    text += '(Evidence fixture - not a real invoice or court document.) Tj 0 -25 Td '
    text += '(Agreed work completed. Payment reminder sent.) Tj ET'
    objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               f'<< /Length {len(text)} >>\nstream\n{text}\nendstream']
    data = b'%PDF-1.4\n'
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{i} 0 obj\n{obj}\nendobj\n'.encode()
    start = len(data)
    data += f'xref\n0 6\n0000000000 65535 f \n'.encode()
    data += ''.join(f'{offset:010} 00000 n \n' for offset in offsets[1:]).encode()
    return data + f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()


def png():
    # A small illustrative receipt, made with the standard library only.
    width, height = 240, 160
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            ink = 20 < x < 220 and (y in range(20, 35) or any(y in range(n, n + 4) for n in [55, 75, 95, 130]))
            raw.extend((40, 70, 85) if ink else (246, 244, 239))
    def chunk(tag, payload):
        return struct.pack('>I', len(payload)) + tag + payload + struct.pack('>I', zlib.crc32(tag + payload))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')


def quoted(value):
    return "'" + str(value).replace("'", "''") + "'"


def seed_sql(kind, count, evidence):
    schema = 'civil' if kind == 'MC' else 'legacy_cmc'
    holidays = json.loads((ROOT / 'seed/bank-holidays.json').read_text())['england-and-wales']['events']
    holiday_sql = ',\n'.join(f"({quoted(e['date'])}, {quoted(e['title'])})" for e in holidays)
    # Temporary rows unify the modern relational and legacy JSON representations.
    sql = f"""-- Generated by scripts/generate-seed.py. Synthetic data only.
-- Caller supplies the transaction. Relative dates use the England/Wales court calendar.
INSERT INTO {schema}.bank_holidays(date, title) VALUES
{holiday_sql}
ON CONFLICT (date) DO UPDATE SET title = EXCLUDED.title;
CREATE TEMP TABLE demo_seed_claims ON COMMIT DROP AS
WITH source AS (
  SELECT item AS data FROM jsonb_array_elements($seed${json.dumps(claims(kind, count), separators=(',', ':'))}$seed$::jsonb) AS item
), dates AS (
  SELECT data, COALESCE((data->>'issueFixed')::date,
    (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/London')::date + (data->>'issueOffset')::int) AS issue_date
  FROM source
), deadlines AS (
  SELECT data, issue_date, issue_date + 5 AS service_date,
    (SELECT day::date FROM generate_series(issue_date + 19 + CASE WHEN (data->>'acknowledged')::boolean THEN 28 ELSE 0 END,
      issue_date + 35 + CASE WHEN (data->>'acknowledged')::boolean THEN 28 ELSE 0 END, interval '1 day') AS day
     WHERE extract(isodow from day) < 6 AND NOT EXISTS
       (SELECT 1 FROM {schema}.bank_holidays h WHERE h.date = day::date)
     ORDER BY day LIMIT 1) AS deadline,
    (data->>'principalAmount')::numeric AS principal,
    CASE WHEN (data->>'interestClaimed')::boolean THEN round((data->>'principalAmount')::numeric * 0.08 * 45 / 365, 2) ELSE 0 END AS interest_amount
  FROM dates
), fees AS (
  SELECT *, CASE WHEN principal + interest_amount <= 300 THEN 35
    WHEN principal + interest_amount <= 500 THEN 50 WHEN principal + interest_amount <= 1000 THEN 70
    WHEN principal + interest_amount <= 1500 THEN 80 WHEN principal + interest_amount <= 3000 THEN 115
    WHEN principal + interest_amount <= 5000 THEN 205 WHEN principal + interest_amount <= 10000 THEN 455
    ELSE least(10000, floor((principal + interest_amount) * 5) / 100) END AS fee
  FROM deadlines
)
SELECT data - ARRAY['issueOffset','issueFixed','interestClaimed','index'] || jsonb_build_object(
  'interest', jsonb_build_object('claimed', (data->>'interestClaimed')::boolean, 'rate', 8, 'startDate', issue_date - 45),
  'interestAmount', interest_amount, 'dailyInterest', CASE WHEN (data->>'interestClaimed')::boolean THEN round(principal * 0.08 / 365, 6) ELSE 0 END,
  'fee', fee, 'totalAmount', principal + interest_amount + fee,
  'timeline', jsonb_build_array(jsonb_build_object('date', issue_date - 45, 'description', 'Payment became due under the agreement'),
    jsonb_build_object('date', issue_date - 14, 'description', 'Written reminder sent; no payment received')),
  'createdAt', (issue_date - 3 + time '10:00') AT TIME ZONE 'Europe/London',
  'updatedAt', (issue_date + CASE WHEN data->>'state' IN ('RESPONDED','SETTLED','TRANSFERRED') THEN 12 WHEN data->>'state' = 'AWAITING_DEFAULT_JUDGMENT' THEN 50 ELSE 0 END + time '12:00') AT TIME ZONE 'Europe/London',
  'issuedAt', CASE WHEN data->>'state' <> 'DRAFT' THEN (issue_date + time '10:00') AT TIME ZONE 'Europe/London' END,
  'serviceDate', CASE WHEN data->>'state' <> 'DRAFT' THEN service_date END,
  'responseDeadline', CASE WHEN data->>'state' <> 'DRAFT' THEN deadline END,
  'eligibleForDefaultJudgment', data->>'state' = 'AWAITING_DEFAULT_JUDGMENT',
  'judgmentRequestedAt', NULL,
  'response', CASE WHEN data->>'state' IN ('RESPONDED','SETTLED','TRANSFERRED') THEN jsonb_build_object(
    'type', CASE WHEN (data->>'index')::int % 3 = 0 THEN 'ADMIT_ALL' WHEN (data->>'index')::int % 3 = 1 THEN 'ADMIT_PART' ELSE 'REJECT' END,
    'admittedAmount', CASE WHEN (data->>'index')::int % 3 = 0 THEN principal WHEN (data->>'index')::int % 3 = 1 THEN round(principal / 2, 2) ELSE 0 END,
    'reason', 'Synthetic response: the parties disagree about the agreed scope of work.',
    'statementOfTruth', true, 'paymentOption', 'IMMEDIATELY',
    'submittedAt', (issue_date + 10 + time '14:00') AT TIME ZONE 'Europe/London') END
) AS data FROM fees;
"""
    if kind == 'MC':
        sql += """INSERT INTO civil.claims(id, reference, claimant_id, state, claimant, defendant, amount_breakdown,
  principal_amount, interest, interest_amount, daily_interest, fee, total_amount, reason, timeline,
  security_code, acknowledged, created_at, updated_at, issued_at, service_date, response_deadline, response, version)
SELECT (data->>'id')::uuid, data->>'reference', data->>'claimantId', data->>'state', data->'claimant', data->'defendant', data->'amountBreakdown',
  (data->>'principalAmount')::numeric, data->'interest', (data->>'interestAmount')::numeric, (data->>'dailyInterest')::numeric,
  (data->>'fee')::numeric, (data->>'totalAmount')::numeric, data->>'reason', data->'timeline', data->>'securityCode',
  (data->>'acknowledged')::boolean, (data->>'createdAt')::timestamptz, (data->>'updatedAt')::timestamptz,
  (data->>'issuedAt')::timestamptz, (data->>'serviceDate')::date, (data->>'responseDeadline')::date,
  NULLIF(data->'response', 'null'::jsonb), 0 FROM demo_seed_claims;
"""
    else:
        sql += """INSERT INTO legacy_cmc.claims(id, reference, claimant_id, state, claim_data, security_code, service_date, response_deadline, created_at, updated_at)
SELECT (data->>'id')::uuid, data->>'reference', data->>'claimantId', data->>'state', data::text,
  data->>'securityCode', (data->>'serviceDate')::date, (data->>'responseDeadline')::date,
  (data->>'createdAt')::timestamptz, (data->>'updatedAt')::timestamptz FROM demo_seed_claims;
INSERT INTO legacy_cmc.offers(id, claim_id, amount, status, offer_data, created_at)
SELECT md5(reference || '-offer')::uuid, id, round((claim_data::jsonb->>'principalAmount')::numeric / 2, 2),
  CASE WHEN state = 'SETTLED' THEN 'ACCEPTED' ELSE 'PENDING' END,
  jsonb_build_object('amount', round((claim_data::jsonb->>'principalAmount')::numeric / 2, 2), 'reason', 'Synthetic settlement offer')::text,
  updated_at FROM legacy_cmc.claims WHERE state IN ('RESPONDED', 'SETTLED');
"""
    sql += f"""INSERT INTO {schema}.claim_events(id, claim_id, type, description, actor, created_at)
SELECT md5(reference || '-created')::uuid, id, 'CLAIM_CREATED', 'Draft claim created', 'demo-claimant', created_at FROM {schema}.claims;
INSERT INTO {schema}.claim_events(id, claim_id, type, description, actor, created_at)
SELECT md5((data->>'reference') || '-issued')::uuid, (data->>'id')::uuid, 'CLAIM_ISSUED', 'Demo payment confirmed and claim issued', 'system',
  (data->>'issuedAt')::timestamptz FROM demo_seed_claims WHERE data->>'state' <> 'DRAFT';
INSERT INTO {schema}.claim_events(id, claim_id, type, description, actor, created_at)
SELECT md5((data->>'reference') || '-response')::uuid, (data->>'id')::uuid, 'DEFENDANT_RESPONSE', 'Defendant submitted a response', 'demo-defendant',
  (data->'response'->>'submittedAt')::timestamptz FROM demo_seed_claims WHERE data->'response' <> 'null'::jsonb;
INSERT INTO {schema}.claim_events(id, claim_id, type, description, actor, created_at)
SELECT md5(reference || '-state')::uuid, id, state, 'Claim moved to ' || lower(replace(state, '_', ' ')), 'system', updated_at
FROM {schema}.claims WHERE state NOT IN ('DRAFT', 'ISSUED');
INSERT INTO {schema}.case_notes(id, claim_id, text, author, created_at)
SELECT md5(reference || '-note')::uuid, id, 'Synthetic demo case reviewed. Supporting documents are available in the Documents tab.', 'demo-caseworker', updated_at
FROM {schema}.claims WHERE state IN ('RESPONDED', 'TRANSFERRED', 'SETTLED');
"""
    for name, content_type, content, divisor in evidence:
        sql += f"""INSERT INTO {schema}.documents(id, claim_id, file_name, content_type, size_bytes, content, scan_status, created_at)
SELECT md5(reference || '-{name}')::uuid, id, '{name}', '{content_type}', {len(content)}, decode('{content.hex()}', 'hex'), 'CLEAN', created_at
FROM {schema}.claims WHERE substring(reference from 6)::int % {divisor} = 1;
"""
    return sql + f"SELECT setval('{schema}.claim_reference_seq', {count + 1}, false);\nDROP TABLE demo_seed_claims;\n"


def main():
    evidence = [('sample-invoice.pdf', 'application/pdf', pdf(), 3),
                ('sample-receipt.png', 'image/png', png(), 5)]
    for name, _, content, _ in evidence:
        (ROOT / 'seed/evidence' / name).write_bytes(content)
    for kind, count, schema, target in [
        ('MC', 160, 'civil', 'civil-case-api/src/main/resources/db/migration/seed/V300__demo_seed.sql'),
        ('LC', 40, 'legacy_cmc', 'seed/legacy/migrations/V300__demo_seed.sql')]:
        sql = seed_sql(kind, count, evidence)
        (ROOT / target).write_text(sql)
        reset = ROOT / 'seed' / ('modern' if kind == 'MC' else 'legacy') / 'reset.sql'
        reset.write_text(f'-- Destructive demo reset: endpoint checks enable flag and token first.\nDELETE FROM {schema}.claims;\nDELETE FROM {schema}.bank_holidays;\n' + sql)


if __name__ == '__main__':
    main()
