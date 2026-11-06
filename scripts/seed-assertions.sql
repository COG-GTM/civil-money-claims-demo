DO $test$
BEGIN
  ASSERT (SELECT count(*) = 160 FROM civil.claims), 'modern count';
  ASSERT (SELECT count(*) = 40 FROM legacy_cmc.claims), 'legacy count';
  ASSERT (SELECT count(DISTINCT state) = 7 FROM civil.claims), 'all modern states';
  ASSERT (SELECT count(DISTINCT state) = 7 FROM legacy_cmc.claims), 'all legacy states';
  ASSERT (SELECT min(principal_amount) = 50 AND max(principal_amount) = 25000 FROM civil.claims), 'money range';
  ASSERT (SELECT bool_and(total_amount = principal_amount + interest_amount + fee) FROM civil.claims), 'decimal totals';
  ASSERT (SELECT bool_and(security_code ~ '^[A-Z0-9]{8}$') FROM civil.claims), 'security code shape';
  ASSERT (SELECT bool_and(claimant_id = 'demo-claimant') FROM civil.claims), 'dashboard identity';
  ASSERT (SELECT security_code = 'DEMO2026' AND state = 'AWAITING_RESPONSE' AND response_deadline > CURRENT_DATE FROM civil.claims WHERE reference = '000MC001'), 'modern smoke';
  ASSERT (SELECT security_code = 'DEMO2026' AND state = 'AWAITING_RESPONSE' AND response_deadline > CURRENT_DATE FROM legacy_cmc.claims WHERE reference = '000LC001'), 'legacy smoke';
  ASSERT (SELECT state = 'AWAITING_DEFAULT_JUDGMENT' AND response_deadline < CURRENT_DATE FROM civil.claims WHERE reference = '000MC002'), 'modern expired';
  ASSERT (SELECT state = 'AWAITING_DEFAULT_JUDGMENT' AND response_deadline < CURRENT_DATE FROM legacy_cmc.claims WHERE reference = '000LC002'), 'legacy expired';
  ASSERT (SELECT response_deadline = '2026-04-07' FROM civil.claims WHERE reference = '000MC157'), 'Easter rollover';
  ASSERT (SELECT response_deadline = '2025-12-29' FROM civil.claims WHERE reference = '000MC158'), 'Christmas rollover';
  ASSERT (SELECT response_deadline = '2026-03-30' FROM civil.claims WHERE reference = '000MC159'), 'spring DST';
  ASSERT (SELECT response_deadline = '2026-10-26' FROM civil.claims WHERE reference = '000MC160'), 'autumn DST';
  ASSERT (SELECT bool_and(extract(isodow FROM response_deadline) < 6) FROM civil.claims), 'weekday deadlines';
  ASSERT NOT EXISTS (SELECT 1 FROM civil.claims c JOIN civil.bank_holidays h ON h.date = c.response_deadline), 'holiday deadlines';
  ASSERT (SELECT bool_and(size_bytes = octet_length(content)) AND count(*) > 50 FROM civil.documents), 'modern evidence';
  ASSERT (SELECT bool_and(size_bytes = octet_length(content)) AND count(*) > 10 FROM legacy_cmc.documents), 'legacy evidence';
  ASSERT (SELECT bool_and(substring(content FROM 1 FOR 5) = convert_to('%PDF-', 'UTF8')) FROM civil.documents WHERE content_type = 'application/pdf'), 'valid PDF signature';
  ASSERT (SELECT bool_and(substring(content FROM 1 FOR 8) = decode('89504e470d0a1a0a', 'hex')) FROM civil.documents WHERE content_type = 'image/png'), 'valid PNG signature';
  ASSERT NOT EXISTS (SELECT 1 FROM legacy_cmc.claims WHERE claim_data::jsonb->>'reference' <> reference OR claim_data::jsonb->>'state' <> state
    OR (claim_data::jsonb->>'responseDeadline')::date IS DISTINCT FROM response_deadline), 'legacy scalar/JSON consistency';
  ASSERT (SELECT last_value = 161 AND NOT is_called FROM civil.claim_reference_seq), 'modern next reference';
  ASSERT (SELECT last_value = 41 AND NOT is_called FROM legacy_cmc.claim_reference_seq), 'legacy next reference';
END
$test$;
