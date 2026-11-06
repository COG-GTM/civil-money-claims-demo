package uk.gov.hmcts.demo.civil.service;

import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.demo.civil.document.*;
import uk.gov.hmcts.demo.civil.domain.*;
import uk.gov.hmcts.demo.civil.persistence.*;
import uk.gov.hmcts.demo.civil.web.ApiException;
import uk.gov.hmcts.demo.civil.web.ClaimModels.*;
import uk.gov.hmcts.demo.civil.workflow.*;

@Service
public class ClaimService implements WorkflowCasePort {
    private static final char[] CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final ClaimRepository claims;
    private final ClaimEventRepository events;
    private final CaseNoteRepository notes;
    private final BankHolidayRepository holidays;
    private final ClaimReferenceGenerator references;
    private final FeeQuoteService quotes;
    private final DeadlineCalculator deadlines;
    private final ClaimStateMachine states;
    private final DocumentStore documents;
    private final ClaimFormGenerator forms;
    private final ObjectProvider<ClaimWorkflow> workflow;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Validator validator;

    public ClaimService(ClaimRepository claims, ClaimEventRepository events, CaseNoteRepository notes,
            BankHolidayRepository holidays, ClaimReferenceGenerator references, FeeQuoteService quotes,
            DeadlineCalculator deadlines, ClaimStateMachine states, DocumentStore documents,
            ClaimFormGenerator forms, ObjectProvider<ClaimWorkflow> workflow, PlatformTransactionManager manager,
            Clock clock, Validator validator) {
        this.claims=claims; this.events=events; this.notes=notes; this.holidays=holidays; this.references=references;
        this.quotes=quotes; this.deadlines=deadlines; this.states=states; this.documents=documents;
        this.forms=forms; this.workflow=workflow; this.transactions=new TransactionTemplate(manager);
        this.clock=clock; this.validator=validator;
    }

    public ClaimView create(ClaimInput input, String actor) {
        return transactions.execute(status -> {
            ClaimEntity claim = new ClaimEntity();
            claim.id=UUID.randomUUID(); claim.reference=references.generate(); claim.securityCode=securityCode();
            claim.createdAt=clock.instant(); claim.updatedAt=claim.createdAt;
            apply(claim,input);
            claims.saveAndFlush(claim);
            event(claim,"CLAIM_CREATED","Draft claim created",actor);
            return view(claim);
        });
    }

    public ClaimView update(String reference, ClaimInput input, String actor) {
        return transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            require(claim.state == ClaimState.DRAFT,"Only a draft claim can be edited");
            apply(claim,input);
            claim.updatedAt=clock.instant();
            claims.saveAndFlush(claim);
            event(claim,"CLAIM_UPDATED","Draft claim updated",actor);
            return view(claim);
        });
    }

    public ClaimView get(String reference) { return view(required(reference)); }

    public ClaimPage list(ClaimState state, LocalDate from, LocalDate to, String claimantId, int page, int size) {
        if (page<0 || size<1 || size>500) throw ApiException.invalid("page","Use a non-negative page and size between 1 and 500");
        if (from != null && to != null && from.isAfter(to)) throw ApiException.invalid("from","From date must not be after to date");
        Specification<ClaimEntity> filter=(root,query,cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates=new ArrayList<>();
            if (state != null) predicates.add(cb.equal(root.get("state"),state));
            if (claimantId != null && !claimantId.isBlank()) predicates.add(cb.equal(root.get("claimantId"),claimantId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),from.atStartOfDay(DeadlineCalculator.COURT_ZONE).toInstant()));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"),to.plusDays(1).atStartOfDay(DeadlineCalculator.COURT_ZONE).toInstant()));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<ClaimEntity> result=claims.findAll(filter,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","reference")));
        return new ClaimPage(result.getContent().stream().map(this::view).toList(),result.getTotalElements(),page,size);
    }

    public ClaimView lookup(LookupInput input) {
        String reference=normalize(input.reference());
        String code=normalize(input.securityCode());
        ClaimEntity claim=claims.findByReference(reference).orElse(null);
        if (claim == null || claim.state == ClaimState.DRAFT || !MessageDigest.isEqual(
                code.getBytes(StandardCharsets.UTF_8),claim.securityCode.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403,"INVALID_SECURITY_CODE","The claim reference or security code is incorrect");
        }
        return view(claim);
    }

    public ClaimView issue(String reference, IssueInput input, String actor) {
        ClaimView issued=transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            require(claim.state==ClaimState.DRAFT,"Only a draft claim can be issued");
            validateParty("claimant",claim.claimant);
            validateParty("defendant",claim.defendant);
            if (claim.reason.isBlank()) throw ApiException.invalid("reason","Explain why you are claiming");
            if (claim.principalAmount.signum()<=0) throw ApiException.invalid("amountBreakdown","Enter a claim amount greater than zero");
            recalculate(claim);
            claim.issuedAt=clock.instant();
            claim.serviceDate=deadlines.serviceDate(LocalDate.now(clock));
            claim.responseDeadline=deadlines.calculate(claim.serviceDate,false,holidays.dates());
            changeState(claim,ClaimState.ISSUED);
            claims.saveAndFlush(claim);
            event(claim,"CLAIM_ISSUED","Claim issued; demo payment confirmed",actor);
            return view(claim);
        });
        // The committed ISSUED claim is visible to Camunda before starting its process.
        withWorkflow(w -> w.issued(issued.reference(),issued.responseDeadline()));
        return get(reference);
    }

    public ClaimView acknowledge(String reference, String actor) {
        ClaimView acknowledged=transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            require(claim.state==ClaimState.AWAITING_RESPONSE && claim.response==null
                && claim.judgmentRequestedAt==null,"This claim cannot be acknowledged");
            require(claim.responseDeadline!=null && !LocalDate.now(clock).isAfter(claim.responseDeadline),
                "The response deadline has passed");
            if (!claim.acknowledged) {
                claim.acknowledged=true;
                claim.responseDeadline=deadlines.calculate(claim.serviceDate,true,holidays.dates());
                claim.updatedAt=clock.instant();
                event(claim,"CLAIM_ACKNOWLEDGED","Response deadline extended by the demo acknowledgment rule",actor);
                claims.saveAndFlush(claim);
            }
            return view(claim);
        });
        withWorkflow(w -> w.acknowledged(acknowledged.reference(),acknowledged.responseDeadline()));
        return get(reference);
    }

    public ClaimView respond(String reference, DefendantResponse input, String actor) {
        ClaimView responded=transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            require(claim.response==null && claim.judgmentRequestedAt==null,"A response or judgment request has already been recorded");
            validateResponse(claim,input);
            changeState(claim,ClaimState.RESPONDED);
            BigDecimal admittedAmount=switch(input.type()) {
                case ADMIT_ALL -> claim.totalAmount;
                case ADMIT_PART -> input.admittedAmount();
                case REJECT -> BigDecimal.ZERO;
            };
            claim.response=new DefendantResponse(input.type(),admittedAmount,input.reason(),
                input.paymentOption(),input.paymentDate(),input.instalmentAmount(),true,clock.instant());
            event(claim,"DEFENDANT_RESPONSE","Defendant submitted a response: "+input.type(),actor);
            claims.saveAndFlush(claim);
            return view(claim);
        });
        withWorkflow(w -> w.responded(responded.reference()));
        return get(reference);
    }

    public ClaimView requestJudgment(String reference, String actor) {
        return transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            require(eligible(claim),"Default judgment is not available for this claim");
            if (claim.state==ClaimState.AWAITING_RESPONSE) changeState(claim,ClaimState.AWAITING_DEFAULT_JUDGMENT);
            claim.judgmentRequestedAt=clock.instant(); claim.updatedAt=clock.instant();
            event(claim,"DEFAULT_JUDGMENT_REQUESTED","Claimant requested judgment by default",actor);
            claims.saveAndFlush(claim);
            return view(claim);
        });
    }

    public ClaimView transition(String reference, StateInput input, String actor) {
        if (input.state()!=ClaimState.SETTLED && input.state()!=ClaimState.TRANSFERRED) {
            throw ApiException.invalid("state","Select SETTLED or TRANSFERRED");
        }
        return transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            changeState(claim,input.state());
            event(claim,"STATE_CHANGED",input.reason()==null || input.reason().isBlank()
                ? "Claim marked "+input.state() : input.reason(),actor);
            claims.saveAndFlush(claim);
            return view(claim);
        });
    }

    public List<ClaimEvent> events(String reference) { return events.findByClaimIdOrderByCreatedAtAsc(required(reference).id); }
    public List<CaseNote> notes(String reference) { return notes.findByClaimIdOrderByCreatedAtAsc(required(reference).id); }
    public CaseNote addNote(String reference, NoteInput input, String actor) {
        return transactions.execute(status -> {
            ClaimEntity claim=locked(reference);
            CaseNote note=new CaseNote();
            note.id=UUID.randomUUID(); note.claimId=claim.id; note.text=input.text();
            note.author=input.author()==null ? actor : input.author(); note.createdAt=clock.instant();
            notes.saveAndFlush(note);
            event(claim,"CASE_NOTE_ADDED","Case note added",actor);
            return note;
        });
    }

    @Override
    public void generateClaimForm(String reference) {
        transactions.executeWithoutResult(status -> {
            ClaimEntity claim=locked(reference);
            require(claim.state!=ClaimState.DRAFT,"A draft has no issued claim form");
            if (!documents.existsClaimForm(claim.id)) {
                byte[] pdf=forms.generate(claim);
                documents.save(claim.id,claim.reference,"claim-form-"+claim.reference+".pdf","application/pdf",
                    pdf,"Demo generated claim form","CLAIM_FORM");
                event(claim,"CLAIM_FORM_GENERATED","Demo claim form generated","system");
            }
        });
    }

    @Override
    public void markAwaitingResponse(String reference) {
        transactions.executeWithoutResult(status -> {
            ClaimEntity claim=locked(reference);
            if (claim.state!=ClaimState.ISSUED) return;
            changeState(claim,ClaimState.AWAITING_RESPONSE);
            event(claim,"AWAITING_RESPONSE","Awaiting the defendant response","system");
            claims.saveAndFlush(claim);
        });
    }

    @Override
    public void markAwaitingDefaultJudgment(String reference) {
        transactions.executeWithoutResult(status -> {
            ClaimEntity claim=locked(reference);
            if (claim.state!=ClaimState.AWAITING_RESPONSE || !eligible(claim)) return;
            changeState(claim,ClaimState.AWAITING_DEFAULT_JUDGMENT);
            event(claim,"RESPONSE_DEADLINE_PASSED","Response deadline passed without a response","system");
            claims.saveAndFlush(claim);
        });
    }

    @Override
    public List<ActiveClaim> activeClaims() {
        return claims.findByStateIn(List.of(ClaimState.ISSUED,ClaimState.AWAITING_RESPONSE)).stream()
            .filter(c -> c.response==null && c.judgmentRequestedAt==null && c.responseDeadline!=null)
            .map(c -> new ActiveClaim(c.reference,c.responseDeadline)).toList();
    }

    private void apply(ClaimEntity claim, ClaimInput input) {
        if (input.claimantId()!=null) {
            if (input.claimantId().isBlank()) throw ApiException.invalid("claimantId","Enter a claimant identifier");
            claim.claimantId=input.claimantId();
        }
        if (input.claimant()!=null) claim.claimant=input.claimant();
        if (input.defendant()!=null) claim.defendant=input.defendant();
        if (input.amountBreakdown()!=null) claim.amountBreakdown=input.amountBreakdown();
        if (input.interest()!=null) claim.interest=input.interest();
        if (input.reason()!=null) claim.reason=input.reason();
        if (input.timeline()!=null) claim.timeline=input.timeline();
        recalculate(claim);
    }

    private void recalculate(ClaimEntity claim) {
        FeeQuote quote=quotes.quote(claim.amountBreakdown,claim.interest,LocalDate.now(clock),true);
        claim.principalAmount=quote.principalAmount(); claim.interestAmount=quote.interestAmount();
        claim.dailyInterest=quote.dailyInterest(); claim.fee=quote.fee(); claim.totalAmount=quote.totalAmount();
    }

    private void validateParty(String prefix, Party party) {
        Map<String,String> errors=new TreeMap<>();
        if (party==null) errors.put(prefix,"Enter party details");
        else validator.validate(party).forEach(v -> errors.put(prefix+"."+v.getPropertyPath(),v.getMessage()));
        if (!errors.isEmpty()) throw new ApiException(400,"VALIDATION_ERROR","Complete the party details",errors);
    }

    private void validateResponse(ClaimEntity claim, DefendantResponse response) {
        if (response.type()==ResponseType.ADMIT_PART && (response.admittedAmount()==null
                || response.admittedAmount().signum()<=0 || response.admittedAmount().compareTo(claim.totalAmount)>=0)) {
            throw ApiException.invalid("admittedAmount","Enter an amount greater than zero and less than the claim total");
        }
        if (response.type()!=ResponseType.ADMIT_ALL && (response.reason()==null || response.reason().isBlank())) {
            throw ApiException.invalid("reason","Explain why you disagree with the claim");
        }
        if (response.paymentOption()==PaymentOption.BY_DATE && (response.paymentDate()==null
                || response.paymentDate().isBefore(LocalDate.now(clock)))) {
            throw ApiException.invalid("paymentDate","Enter a payment date today or in the future");
        }
        if (response.paymentOption()==PaymentOption.INSTALMENTS && response.instalmentAmount()==null) {
            throw ApiException.invalid("instalmentAmount","Enter an instalment amount");
        }
        BigDecimal owed=response.type()==ResponseType.ADMIT_PART ? response.admittedAmount() : claim.totalAmount;
        if (response.instalmentAmount()!=null && response.instalmentAmount().compareTo(owed)>0) {
            throw ApiException.invalid("instalmentAmount","The instalment must not exceed the admitted amount");
        }
    }

    private void changeState(ClaimEntity claim, ClaimState target) {
        try {
            claim.state=states.transition(claim.state,target,claim.judgmentRequestedAt!=null);
        } catch (IllegalStateException ex) {
            throw ApiException.conflict(ex.getMessage());
        }
        claim.updatedAt=clock.instant();
    }

    private void event(ClaimEntity claim, String type, String description, String actor) {
        ClaimEvent event=new ClaimEvent();
        event.id=UUID.randomUUID(); event.claimId=claim.id; event.type=type; event.description=description;
        event.actor=actor; event.createdAt=clock.instant(); events.save(event);
    }

    private boolean eligible(ClaimEntity claim) {
        return (claim.state==ClaimState.AWAITING_RESPONSE || claim.state==ClaimState.AWAITING_DEFAULT_JUDGMENT)
            && claim.response==null && claim.judgmentRequestedAt==null && claim.responseDeadline!=null
            && LocalDate.now(clock).isAfter(claim.responseDeadline);
    }

    private ClaimView view(ClaimEntity c) {
        return new ClaimView(c.id,c.reference,c.claimantId,c.state,c.claimant,c.defendant,c.amountBreakdown,
            c.principalAmount,c.interest,c.interestAmount,c.dailyInterest,c.fee,c.totalAmount,c.reason,c.timeline,
            c.securityCode,c.createdAt,c.updatedAt,c.issuedAt,c.serviceDate,c.responseDeadline,c.acknowledged,
            eligible(c),c.response,c.judgmentRequestedAt,c.version==null?0:c.version);
    }

    private ClaimEntity required(String reference) {
        return claims.findByReference(normalize(reference)).orElseThrow(ApiException::missing);
    }
    private ClaimEntity locked(String reference) {
        return claims.lockByReference(normalize(reference)).orElseThrow(ApiException::missing);
    }
    private String normalize(String value) { return value.strip().toUpperCase(Locale.ROOT); }
    private void require(boolean condition, String message) { if (!condition) throw ApiException.conflict(message); }
    private void withWorkflow(Consumer<ClaimWorkflow> action) { workflow.ifAvailable(action); }
    private String securityCode() {
        char[] result=new char[8];
        for(int i=0;i<result.length;i++) result[i]=CODE_CHARS[random.nextInt(CODE_CHARS.length)];
        return new String(result);
    }
}
