package uk.gov.hmcts.demo.legacy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.demo.legacy.repository.ClaimRepository;
import uk.gov.hmcts.demo.legacy.repository.OffersRepository;
import uk.gov.hmcts.demo.legacy.repository.ReferenceNumberRepository;
import uk.gov.hmcts.demo.legacy.web.LegacyApiException;
import uk.gov.hmcts.demo.legacy.web.LegacyExceptionHandler;

// Intentionally old-generation: validation, calculations, SQL and state changes live together.
@Service
public class ClaimService {
    private static final Logger LOG = LoggerFactory.getLogger(ClaimService.class);
    private static final Set<String> STATES = Set.of("DRAFT", "ISSUED", "AWAITING_RESPONSE", "RESPONDED",
        "AWAITING_DEFAULT_JUDGMENT", "SETTLED", "TRANSFERRED");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
    private static final String CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final DBI dbi;
    private final ObjectMapper mapper;
    private final LegacyResponseDeadlineCalculator dates;
    private final SecureRandom random = new SecureRandom();
    private final boolean resetEnabled;
    private final String resetToken;
    private final String resetSql;

    public ClaimService(DBI dbi, ObjectMapper mapper, LegacyResponseDeadlineCalculator dates,
            @Value("${demo.reset-enabled:false}") boolean resetEnabled,
            @Value("${demo.reset-token:}") String resetToken,
            @Value("${demo.reset-sql:../seed/legacy/reset.sql}") String resetSql) {
        this.dbi = dbi;
        this.mapper = mapper;
        this.dates = dates;
        this.resetEnabled = resetEnabled;
        this.resetToken = resetToken;
        this.resetSql = resetSql;
    }

    public ObjectNode quote(ObjectNode input) {
        return calculate(input, false);
    }

    public ObjectNode create(ObjectNode input, String actor) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = mapper.createObjectNode();
            applyAnswers(claim, input);
            claim.put("id", UUID.randomUUID().toString());
            claim.put("reference", String.format(Locale.ROOT, "000LC%03d", handle.attach(ReferenceNumberRepository.class).nextNumber()));
            claim.put("state", "DRAFT");
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 8; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            claim.put("securityCode", code.toString());
            String now = Instant.now().toString();
            claim.put("createdAt", now).put("updatedAt", now).put("version", 0).put("acknowledged", false);
            claim.putNull("issuedAt").putNull("serviceDate").putNull("responseDeadline").putNull("response").putNull("judgmentRequestedAt");
            claim.put("eligibleForDefaultJudgment", false);
            claims.insert(text(claim, "id"), text(claim, "reference"), text(claim, "claimantId"), claim.toString(), code.toString(), now);
            event(claims, claim, "CLAIM_CREATED", "Legacy draft created", actor);
            return claim;
        });
    }

    public ObjectNode update(String reference, ObjectNode input, String actor) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            requireState(claim, "DRAFT");
            applyAnswers(claim, input);
            save(claims, claim);
            event(claims, claim, "DRAFT_UPDATED", "Draft answers updated", actor);
            return claim;
        });
    }

    public ObjectNode get(String reference) {
        return transaction(handle -> load(handle.attach(ClaimRepository.class), reference));
    }

    public ObjectNode lookup(ObjectNode input) {
        String reference = requiredText(input, "reference", 30).trim().toUpperCase(Locale.ROOT);
        String code = requiredText(input, "securityCode", 100).trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{8}")) throw invalid("securityCode", "Enter the 8-character security code");
        ObjectNode claim = get(reference);
        if (!constantEquals(text(claim, "securityCode"), code)) {
            throw new LegacyApiException(403, "INVALID_SECURITY_CODE", "The claim reference and security code do not match");
        }
        return claim;
    }

    public ObjectNode list(String state, String from, String to, String claimantId, int page, int size) {
        if (state != null && !STATES.contains(state)) throw invalid("state", "Choose a valid claim state");
        if (page < 0 || size < 1 || size > 500) throw invalid("page", "Use page 0 or above and a size between 1 and 500");
        if (claimantId != null && claimantId.length() > 100) throw invalid("claimantId", "Claimant ID is too long");
        Date start = from == null ? null : date(from, "from");
        Date end = to == null ? null : date(to, "to");
        if (start != null && end != null && start.after(end)) throw invalid("to", "The end date must be on or after the start date");
        expireDeadlines();
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode items = result.putArray("items");
            for (String data : claims.list(state, claimantId, from, to, size, (long) page * size)) items.add(view(read(data)));
            result.put("total", claims.count(state, claimantId, from, to)).put("page", page).put("size", size);
            return result;
        });
    }

    public ObjectNode issue(String reference, ObjectNode input, String actor) {
        requireTruth(input);
        requiredText(input, "paymentReference", 100);
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            if (!"DRAFT".equals(text(claim, "state"))) return claim;
            validateParty(claim.path("claimant"), "claimant", true);
            validateParty(claim.path("defendant"), "defendant", true);
            requiredText(claim, "reason", 10000);
            ObjectNode quote = calculate(claim, false);
            claim.setAll(quote);
            claim.remove("calculationDate");
            String now = Instant.now().toString();
            Date serviceDate = dates.serviceDate(new Date());
            claim.put("issuedAt", now).put("serviceDate", dates.format(serviceDate));
            claim.put("responseDeadline", dates.format(dates.responseDeadline(serviceDate, false, claims.bankHolidays())));
            claim.put("state", "ISSUED");
            save(claims, claim);
            event(claims, claim, "CLAIM_ISSUED", "Claim issued after demo payment confirmation", actor);
            claim.put("state", "AWAITING_RESPONSE");
            save(claims, claim);
            event(claims, claim, "AWAITING_RESPONSE", "Defendant notification queued (demo); awaiting response", "system");
            return claim;
        });
    }

    public ObjectNode acknowledge(String reference, String actor) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            if (claim.path("acknowledged").asBoolean()) return claim;
            requireState(claim, "ISSUED", "AWAITING_RESPONSE");
            if (claim.hasNonNull("response") || claim.hasNonNull("judgmentRequestedAt")) throw conflict("This claim can no longer be acknowledged");
            Date serviceDate = date(text(claim, "serviceDate"), "serviceDate");
            claim.put("acknowledged", true).put("responseDeadline", dates.format(dates.responseDeadline(serviceDate, true, claims.bankHolidays())));
            save(claims, claim);
            event(claims, claim, "CLAIM_ACKNOWLEDGED", "Response deadline extended by 28 additional calendar days", actor);
            return claim;
        });
    }

    public ObjectNode respond(String reference, ObjectNode input, String actor) {
        requireTruth(input);
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            requireState(claim, "ISSUED", "AWAITING_RESPONSE", "AWAITING_DEFAULT_JUDGMENT");
            if (claim.hasNonNull("response") || claim.hasNonNull("judgmentRequestedAt")) throw conflict("This claim can no longer be responded to");
            ObjectNode response = validateResponse(input, claim);
            claim.set("response", response);
            claim.put("state", "RESPONDED");
            save(claims, claim);
            event(claims, claim, "DEFENDANT_RESPONSE", "Defendant response submitted: " + text(response, "type"), actor);
            event(claims, claim, "CLAIMANT_NOTIFIED", "Claimant notification queued (demo)", "system");
            return claim;
        });
    }

    public ObjectNode defaultJudgment(String reference, ObjectNode input, String actor) {
        requireTruth(input);
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            if (!eligible(claim)) throw conflict("Default judgment is only available after the response deadline with no response or previous request");
            claim.put("judgmentRequestedAt", Instant.now().toString()).put("state", "AWAITING_DEFAULT_JUDGMENT");
            save(claims, claim);
            event(claims, claim, "DEFAULT_JUDGMENT_REQUESTED", "Claimant requested judgment by default", actor);
            return claim;
        });
    }

    public ObjectNode transition(String reference, ObjectNode input, String actor) {
        String state = requiredText(input, "state", 40);
        if (!Set.of("SETTLED", "TRANSFERRED").contains(state)) throw invalid("state", "Choose SETTLED or TRANSFERRED");
        optionalText(input, "reason", 10000);
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            requireState(claim, "RESPONDED", "AWAITING_DEFAULT_JUDGMENT");
            claim.put("state", state);
            save(claims, claim);
            String reason = text(input, "reason");
            event(claims, claim, "CLAIM_" + state, "Case marked " + state.toLowerCase(Locale.ROOT) + (reason == null ? "" : ": " + reason), actor);
            return claim;
        });
    }

    public ArrayNode events(String reference) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            return array(claims.events(text(load(claims, reference), "id")));
        });
    }

    public ArrayNode notes(String reference) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            return array(claims.notes(text(load(claims, reference), "id")));
        });
    }

    public ObjectNode addNote(String reference, ObjectNode input, String actor) {
        String note = requiredText(input, "text", 10000);
        String author = input.hasNonNull("author") ? requiredText(input, "author", 100) : actor;
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            ObjectNode result = mapper.createObjectNode().put("id", UUID.randomUUID().toString()).put("text", note)
                .put("author", author).put("createdAt", Instant.now().toString());
            claims.note(text(result, "id"), text(claim, "id"), note, author, text(result, "createdAt"));
            event(claims, claim, "CASE_NOTE_ADDED", "Caseworker note added", actor);
            return result;
        });
    }

    public ArrayNode documents(String reference) {
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            ArrayNode result = array(claims.documents(text(claim, "id")));
            for (JsonNode document : result) ((ObjectNode) document).put("claimReference", text(claim, "reference"));
            return result;
        });
    }

    public ObjectNode upload(String reference, MultipartFile file, String description, String actor) {
        if (file == null || file.isEmpty()) throw invalid("file", "Choose a file to upload");
        if (file.getSize() > 10L * 1024 * 1024) throw new LegacyApiException(413, "FILE_TOO_LARGE", "The file must be 10 MiB or smaller");
        if (description != null && description.length() > 10000) throw invalid("description", "Description is too long");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException exception) { throw new IllegalStateException("Could not read uploaded file", exception); }
        String type = contentType(bytes);
        if (!type.equals(file.getContentType())) throw new LegacyApiException(415, "UNSUPPORTED_FILE_TYPE", "Upload a PDF, JPG or PNG file with a matching content type");
        String name = safeFileName(file.getOriginalFilename());
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            if (Set.of("SETTLED", "TRANSFERRED").contains(text(claim, "state"))) throw conflict("Evidence cannot be added to a closed claim");
            ObjectNode result = mapper.createObjectNode().put("id", UUID.randomUUID().toString()).put("claimReference", text(claim, "reference"))
                .put("fileName", name).put("contentType", type).put("sizeBytes", bytes.length).put("scanStatus", "CLEAN").put("createdAt", Instant.now().toString());
            // CLEAN is a demo virus-scan stub, not a production malware scanner.
            claims.document(text(result, "id"), text(claim, "id"), name, type, bytes.length, bytes, text(result, "createdAt"));
            event(claims, claim, "DOCUMENT_UPLOADED", "Evidence uploaded (demo scan: CLEAN)", actor);
            return result;
        });
    }

    public Map<String, Object> download(String reference, String documentId) {
        uuid(documentId, "documentId");
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            String json = claims.document(text(claim, "id"), documentId);
            if (json == null) throw notFound("Document not found");
            ObjectNode metadata = read(json);
            return Map.of("fileName", safeFileName(text(metadata, "fileName")), "contentType", text(metadata, "contentType"),
                "content", claims.documentBody(text(claim, "id"), documentId));
        });
    }

    public void deleteDocument(String reference, String documentId, String actor) {
        uuid(documentId, "documentId");
        transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            requireState(claim, "DRAFT");
            if (claims.deleteDocument(text(claim, "id"), documentId) == 0) throw notFound("Document not found");
            event(claims, claim, "DOCUMENT_DELETED", "Draft evidence deleted", actor);
            return null;
        });
    }

    public ArrayNode offers(String reference) {
        return transaction(handle -> {
            ObjectNode claim = load(handle.attach(ClaimRepository.class), reference);
            return array(handle.attach(OffersRepository.class).list(text(claim, "id")));
        });
    }

    public ObjectNode createOffer(String reference, ObjectNode input, String actor) {
        BigDecimal amount = money(input.get("amount"), "amount");
        optionalText(input, "reason", 10000);
        if (input.hasNonNull("paymentDate") && date(text(input, "paymentDate"), "paymentDate").before(dates.today())) {
            throw invalid("paymentDate", "Choose today or a future date");
        }
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            requireState(claim, "ISSUED", "AWAITING_RESPONSE", "RESPONDED", "AWAITING_DEFAULT_JUDGMENT");
            if (claim.hasNonNull("judgmentRequestedAt")) throw conflict("Judgment has already been requested");
            if (amount.compareTo(claim.path("totalAmount").decimalValue()) > 0) throw invalid("amount", "The offer cannot exceed the claim total");
            ObjectNode offer = mapper.createObjectNode().put("id", UUID.randomUUID().toString()).put("amount", amount)
                .put("status", "PENDING").put("createdAt", Instant.now().toString());
            copy(input, offer, "reason", "paymentDate");
            handle.attach(OffersRepository.class).insert(text(offer, "id"), text(claim, "id"), amount, offer.toString(), text(offer, "createdAt"));
            event(claims, claim, "OFFER_CREATED", "Settlement offer made", actor);
            return offer;
        });
    }

    public ObjectNode updateOffer(String reference, String offerId, ObjectNode input, String actor) {
        uuid(offerId, "offerId");
        String status = requiredText(input, "status", 30);
        if (!Set.of("ACCEPTED", "REJECTED").contains(status)) throw invalid("status", "Choose ACCEPTED or REJECTED");
        return transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            ObjectNode claim = load(claims, reference);
            OffersRepository offers = handle.attach(OffersRepository.class);
            String data = offers.find(text(claim, "id"), offerId);
            if (data == null) throw notFound("Offer not found");
            ObjectNode offer = read(data);
            if (!"PENDING".equals(text(offer, "status"))) {
                if (status.equals(text(offer, "status"))) return offer;
                throw conflict("This offer has already been decided");
            }
            requireState(claim, "ISSUED", "AWAITING_RESPONSE", "RESPONDED", "AWAITING_DEFAULT_JUDGMENT");
            if (claim.hasNonNull("judgmentRequestedAt")) throw conflict("Judgment has already been requested");
            offer.put("status", status);
            offers.update(offerId, text(claim, "id"), status, offer.toString());
            // Agreement alone is not receipt of money: a caseworker records settlement separately.
            event(claims, claim, "OFFER_" + status, "Settlement offer " + status.toLowerCase(Locale.ROOT), actor);
            return offer;
        });
    }

    public ObjectNode reset(String token) {
        if (!resetEnabled || resetToken.isBlank() || token == null || !constantEquals(resetToken, token)) {
            throw new LegacyApiException(403, "RESET_FORBIDDEN", "Demo reset is disabled or the reset token is invalid");
        }
        byte[] sql;
        try { sql = Files.readAllBytes(Path.of(resetSql)); } catch (IOException exception) { throw new IllegalStateException("Demo reset SQL is unavailable", exception); }
        return transaction(handle -> {
            ScriptUtils.executeSqlScript(handle.getConnection(), new ByteArrayResource(sql));
            return mapper.createObjectNode().put("status", "reset").put("claims", handle.attach(ClaimRepository.class).countAll());
        }, true);
    }

    @Scheduled(fixedDelayString = "${demo.deadline-poll-ms:60000}", initialDelay = 1000)
    public void pollDeadlines() {
        try {
            expireDeadlines();
        } catch (RuntimeException exception) {
            LOG.atError().addKeyValue("error.kind", exception.getClass().getName())
                .addKeyValue("error.message", "Legacy deadline polling failed")
                .addKeyValue("error.stack", LegacyExceptionHandler.safeStack(exception)).log("Legacy deadline polling failed");
        }
    }

    public void expireDeadlines() {
        transaction(handle -> {
            ClaimRepository claims = handle.attach(ClaimRepository.class);
            for (String data : claims.expired(dates.format(dates.today()))) expire(claims, read(data));
            return null;
        });
    }

    private ObjectNode load(ClaimRepository claims, String reference) {
        String normalized = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("000LC[0-9]{3,}") || normalized.length() > 30) throw invalid("reference", "Enter a legacy claim reference such as 000LC001");
        String data = claims.findForUpdate(normalized);
        if (data == null) throw notFound("Claim not found");
        ObjectNode claim = read(data);
        expire(claims, claim);
        return view(claim);
    }

    private void expire(ClaimRepository claims, ObjectNode claim) {
        if (Set.of("ISSUED", "AWAITING_RESPONSE").contains(text(claim, "state")) && eligible(claim)) {
            claim.put("state", "AWAITING_DEFAULT_JUDGMENT");
            save(claims, claim);
            event(claims, claim, "RESPONSE_DEADLINE_EXPIRED", "The response deadline has passed", "system");
        }
    }

    private boolean eligible(ObjectNode claim) {
        return Set.of("ISSUED", "AWAITING_RESPONSE", "AWAITING_DEFAULT_JUDGMENT").contains(text(claim, "state"))
            && !claim.hasNonNull("response") && !claim.hasNonNull("judgmentRequestedAt")
            && claim.hasNonNull("responseDeadline") && dates.today().after(dates.parse(text(claim, "responseDeadline")));
    }

    private ObjectNode view(ObjectNode claim) {
        claim.put("eligibleForDefaultJudgment", eligible(claim));
        if (!claim.has("version")) claim.put("version", 0);
        if (!claim.has("acknowledged")) claim.put("acknowledged", false);
        return claim;
    }

    private void save(ClaimRepository claims, ObjectNode claim) {
        claim.put("updatedAt", Instant.now().toString()).put("version", claim.path("version").asLong() + 1);
        view(claim);
        claims.update(text(claim, "reference"), text(claim, "claimantId"), text(claim, "state"), claim.toString(),
            text(claim, "serviceDate"), text(claim, "responseDeadline"), text(claim, "updatedAt"));
    }

    private void event(ClaimRepository claims, ObjectNode claim, String type, String description, String actor) {
        claims.event(UUID.randomUUID().toString(), text(claim, "id"), type, description, actor, Instant.now().toString());
    }

    private void applyAnswers(ObjectNode claim, ObjectNode input) {
        String claimantId = input.hasNonNull("claimantId") ? requiredText(input, "claimantId", 100) : "demo-claimant";
        claim.put("claimantId", claimantId);
        for (String party : List.of("claimant", "defendant")) {
            JsonNode supplied = input.get(party);
            ObjectNode answer = blankParty();
            if (supplied != null && !supplied.isNull()) {
                validateParty(supplied, party, false);
                copy(supplied, answer, "type", "name", "email", "phone");
                if (supplied.hasNonNull("address")) copy(supplied.path("address"), (ObjectNode) answer.get("address"), "line1", "line2", "city", "postcode", "country");
            }
            claim.set(party, answer);
        }
        optionalText(input, "reason", 10000);
        claim.put("reason", input.path("reason").asText(""));
        JsonNode timeline = input.path("timeline");
        ArrayNode entries = mapper.createArrayNode();
        if (!timeline.isMissingNode() && !timeline.isNull()) {
            if (!timeline.isArray() || timeline.size() > 100) throw invalid("timeline", "Enter at most 100 timeline entries");
            for (JsonNode row : timeline) {
                date(requiredText(row, "date", 10), "timeline.date");
                ObjectNode entry = mapper.createObjectNode().put("date", text(row, "date")).put("description", requiredText(row, "description", 10000));
                entries.add(entry);
            }
        }
        claim.set("timeline", entries);
        claim.set("amountBreakdown", input.hasNonNull("amountBreakdown") ? input.get("amountBreakdown").deepCopy() : mapper.createArrayNode());
        ObjectNode interest = mapper.createObjectNode().put("claimed", false).put("rate", 8);
        if (input.hasNonNull("interest")) {
            if (!input.path("interest").isObject()) throw invalid("interest", "Enter valid interest details");
            copy(input.path("interest"), interest, "claimed", "rate", "startDate");
        }
        claim.set("interest", interest);
        claim.setAll(calculate(claim, true));
        claim.remove("calculationDate");
    }

    private ObjectNode calculate(JsonNode input, boolean allowEmpty) {
        JsonNode amounts = input.path("amountBreakdown");
        if (!amounts.isArray() || amounts.size() > 100 || (!allowEmpty && amounts.isEmpty())) throw invalid("amountBreakdown", "Enter between 1 and 100 amounts");
        BigDecimal principal = ZERO;
        for (JsonNode item : amounts) {
            requiredText(item, "reason", 1000);
            BigDecimal amount = money(item.get("amount"), "amountBreakdown.amount");
            if (amount.signum() <= 0) throw invalid("amountBreakdown.amount", "Each amount must be more than zero");
            principal = principal.add(amount);
        }
        if (principal.compareTo(MAX_MONEY) > 0) throw invalid("amountBreakdown", "The total amount is too large");
        Date calculationDate = input.hasNonNull("calculationDate") ? date(text(input, "calculationDate"), "calculationDate") : dates.today();
        BigDecimal interestAmount = ZERO;
        BigDecimal daily = BigDecimal.ZERO.setScale(6);
        JsonNode interest = input.path("interest");
        if (!interest.isMissingNode() && !interest.isNull()) {
            if (!interest.isObject() || !interest.path("claimed").isBoolean()) throw invalid("interest.claimed", "Choose whether to claim interest");
            if (interest.hasNonNull("rate") && (!interest.get("rate").isNumber() || interest.get("rate").decimalValue().compareTo(new BigDecimal("8")) != 0)) {
                throw invalid("interest.rate", "The statutory interest rate is 8%");
            }
            if (interest.hasNonNull("startDate")) date(text(interest, "startDate"), "interest.startDate");
            if (interest.path("claimed").asBoolean()) {
                Date start = date(requiredText(interest, "startDate", 10), "interest.startDate");
                if (start.after(calculationDate)) throw invalid("interest.startDate", "Interest cannot start after the calculation date");
                BigDecimal yearly = principal.multiply(new BigDecimal("0.08"));
                daily = yearly.divide(new BigDecimal("365"), 6, RoundingMode.HALF_UP);
                interestAmount = yearly.multiply(BigDecimal.valueOf(dates.daysBetween(start, calculationDate)))
                    .divide(new BigDecimal("365"), 2, RoundingMode.HALF_UP);
            }
        }
        BigDecimal base = principal.add(interestAmount);
        BigDecimal fee = principal.signum() == 0 && allowEmpty ? ZERO : fee(base);
        BigDecimal total = base.add(fee);
        if (total.compareTo(MAX_MONEY) > 0) throw invalid("amountBreakdown", "The total including interest and fees is too large");
        return mapper.createObjectNode().put("principalAmount", principal).put("interestAmount", interestAmount).put("dailyInterest", daily)
            .put("fee", fee).put("totalAmount", total).put("calculationDate", dates.format(calculationDate));
    }

    private BigDecimal fee(BigDecimal amount) {
        String[] limits = {"300", "500", "1000", "1500", "3000", "5000", "10000"};
        String[] fees = {"35.00", "50.00", "70.00", "80.00", "115.00", "205.00", "455.00"};
        for (int i = 0; i < limits.length; i++) if (amount.compareTo(new BigDecimal(limits[i])) <= 0) return new BigDecimal(fees[i]);
        return amount.compareTo(new BigDecimal("200000")) <= 0 ? amount.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.DOWN) : new BigDecimal("10000.00");
    }

    private ObjectNode validateResponse(ObjectNode input, ObjectNode claim) {
        String type = requiredText(input, "type", 30);
        if (!Set.of("ADMIT_ALL", "ADMIT_PART", "REJECT").contains(type)) throw invalid("type", "Choose an admission, partial admission or rejection");
        ObjectNode result = mapper.createObjectNode().put("type", type).put("statementOfTruth", true).put("submittedAt", Instant.now().toString());
        optionalText(input, "reason", 10000);
        if (!"ADMIT_ALL".equals(type)) requiredText(input, "reason", 10000);
        copy(input, result, "reason");
        BigDecimal admitted = "ADMIT_ALL".equals(type) ? claim.path("totalAmount").decimalValue() : ZERO;
        if ("ADMIT_PART".equals(type)) {
            admitted = money(input.get("admittedAmount"), "admittedAmount");
            if (admitted.signum() <= 0 || admitted.compareTo(claim.path("totalAmount").decimalValue()) >= 0) throw invalid("admittedAmount", "Enter an amount above zero and below the claim total");
        }
        result.put("admittedAmount", admitted);
        if (!"REJECT".equals(type)) {
            String option = requiredText(input, "paymentOption", 30);
            if (!Set.of("IMMEDIATELY", "BY_DATE", "INSTALMENTS").contains(option)) throw invalid("paymentOption", "Choose a valid payment option");
            result.put("paymentOption", option);
            if ("BY_DATE".equals(option)) {
                String paymentDate = requiredText(input, "paymentDate", 10);
                if (date(paymentDate, "paymentDate").before(dates.today())) throw invalid("paymentDate", "Choose today or a future date");
                result.put("paymentDate", paymentDate);
            }
            if ("INSTALMENTS".equals(option)) {
                BigDecimal instalment = money(input.get("instalmentAmount"), "instalmentAmount");
                if (instalment.signum() <= 0 || instalment.compareTo(admitted) > 0) throw invalid("instalmentAmount", "Enter a positive instalment no larger than the admitted amount");
                result.put("instalmentAmount", instalment);
            }
        }
        return result;
    }

    private void validateParty(JsonNode party, String field, boolean complete) {
        if (!party.isObject()) throw invalid(field, "Enter party details");
        if (party.hasNonNull("type") && !Set.of("INDIVIDUAL", "BUSINESS").contains(text(party, "type"))) throw invalid(field + ".type", "Choose a person or business");
        for (String name : List.of("name", "email", "phone")) optionalText(party, name, 255);
        if (complete && (text(party, "name") == null || text(party, "name").isBlank())) throw invalid(field + ".name", "Enter a name");
        JsonNode address = party.path("address");
        if (!address.isMissingNode() && !address.isNull() && !address.isObject()) throw invalid(field + ".address", "Enter an address");
        for (String name : List.of("line1", "line2", "city", "postcode", "country")) optionalText(address, name, 255);
        if (complete) {
            for (String name : List.of("line1", "city", "postcode")) {
                if (text(address, name) == null || text(address, name).isBlank()) throw invalid(field + ".address." + name, "Enter " + name);
            }
        }
    }

    private ObjectNode blankParty() {
        ObjectNode party = mapper.createObjectNode().put("type", "INDIVIDUAL").put("name", "");
        party.putObject("address").put("line1", "").put("city", "").put("postcode", "").put("country", "England");
        return party;
    }

    private String contentType(byte[] bytes) {
        if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "image/jpeg";
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), png)) return "image/png";
        throw new LegacyApiException(415, "UNSUPPORTED_FILE_TYPE", "Upload a PDF, JPG or PNG file");
    }

    private String safeFileName(String value) {
        String name = value == null ? "evidence" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9 ._-]", "_").replaceAll("^\\.+", "").trim();
        if (name.isBlank()) name = "evidence";
        return name.substring(0, Math.min(200, name.length()));
    }

    private BigDecimal money(JsonNode value, String field) {
        if (value == null || !value.isNumber()) throw invalid(field, "Enter an amount in pounds");
        BigDecimal amount = value.decimalValue();
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_MONEY) > 0) throw invalid(field, "Enter a non-negative amount with at most 2 decimal places");
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Date date(String value, String field) {
        try { return dates.parse(value); } catch (IllegalArgumentException exception) { throw invalid(field, "Enter a valid date in YYYY-MM-DD format"); }
    }

    private String requiredText(JsonNode node, String field, int max) {
        optionalText(node, field, max);
        String value = text(node, field);
        if (value == null || value.isBlank()) throw invalid(field, "Enter " + field);
        return value.trim();
    }

    private void optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull() && (!value.isTextual() || value.asText().length() > max)) throw invalid(field, "Enter text no longer than " + max + " characters");
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) if (source.hasNonNull(field)) target.set(field, source.get(field).deepCopy());
    }

    private void requireTruth(JsonNode input) {
        if (!input.path("statementOfTruth").isBoolean() || !input.path("statementOfTruth").booleanValue()) throw invalid("statementOfTruth", "Confirm the statement of truth");
    }

    private void requireState(JsonNode claim, String... allowed) {
        if (!Arrays.asList(allowed).contains(text(claim, "state"))) throw conflict("This action is not available in the current claim state");
    }

    private void uuid(String value, String field) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw invalid(field, "Enter a valid identifier"); }
    }

    private boolean constantEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode read(String data) {
        try { return (ObjectNode) mapper.readTree(data); } catch (JsonProcessingException exception) { throw new IllegalStateException("Stored legacy JSON is invalid", exception); }
    }

    private ArrayNode array(List<String> rows) {
        ArrayNode result = mapper.createArrayNode();
        for (String row : rows) result.add(read(row));
        return result;
    }

    private LegacyApiException invalid(String field, String message) {
        return new LegacyApiException(400, "VALIDATION_ERROR", "Check the information you entered", Map.of(field, message));
    }

    private LegacyApiException conflict(String message) { return new LegacyApiException(409, "INVALID_STATE", message); }
    private LegacyApiException notFound(String message) { return new LegacyApiException(404, "NOT_FOUND", message); }

    private <T> T transaction(Function<Handle, T> action) { return transaction(action, false); }

    private <T> T transaction(Function<Handle, T> action, boolean resetting) {
        try (Handle handle = dbi.open()) {
            handle.begin();
            try {
                // Reset excludes in-flight mutations; ordinary requests still lock individual claims only.
                handle.execute(resetting ? "SELECT pg_advisory_xact_lock(730031)" : "SELECT pg_advisory_xact_lock_shared(730031)");
                T result = action.apply(handle);
                handle.commit();
                return result;
            } catch (RuntimeException | Error exception) {
                handle.rollback();
                throw exception;
            }
        }
    }
}
