package uk.gov.hmcts.demo.civil.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import uk.gov.hmcts.demo.civil.domain.ClaimState;
import uk.gov.hmcts.demo.civil.persistence.*;
import uk.gov.hmcts.demo.civil.service.*;
import uk.gov.hmcts.demo.civil.web.ClaimModels.*;

@RestController
@RequestMapping("/api")
public class ClaimController {
    private final ClaimService claims;
    private final FeeQuoteService fees;
    public ClaimController(ClaimService claims, FeeQuoteService fees) { this.claims=claims; this.fees=fees; }

    @PostMapping("/fees/quote")
    public FeeQuote quote(@Valid @RequestBody FeeQuoteInput input) {
        return fees.quote(input.amountBreakdown(),input.interest(),input.calculationDate(),false);
    }
    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView create(@Valid @RequestBody ClaimInput input, HttpServletRequest request) {
        return claims.create(input,actor(request));
    }
    @GetMapping("/claims")
    public ClaimPage list(@RequestParam(required=false) ClaimState state, @RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) String claimantId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return claims.list(state,from,to,claimantId,page,size);
    }
    @PostMapping("/claims/lookup")
    public ClaimView lookup(@Valid @RequestBody LookupInput input) { return claims.lookup(input); }
    @GetMapping("/claims/{reference}")
    public ClaimView get(@PathVariable String reference) { return claims.get(reference); }
    @PutMapping("/claims/{reference}")
    public ClaimView update(@PathVariable String reference,@Valid @RequestBody ClaimInput input,HttpServletRequest request) {
        return claims.update(reference,input,actor(request));
    }
    @PostMapping("/claims/{reference}/issue")
    public ClaimView issue(@PathVariable String reference,@Valid @RequestBody IssueInput input,HttpServletRequest request) {
        return claims.issue(reference,input,actor(request));
    }
    @PostMapping("/claims/{reference}/acknowledge")
    public ClaimView acknowledge(@PathVariable String reference,@RequestBody java.util.Map<String,Object> input,HttpServletRequest request) {
        return claims.acknowledge(reference,actor(request));
    }
    @PostMapping("/claims/{reference}/response")
    public ClaimView response(@PathVariable String reference,@Valid @RequestBody DefendantResponse input,HttpServletRequest request) {
        return claims.respond(reference,input,actor(request));
    }
    @PostMapping("/claims/{reference}/default-judgment")
    public ClaimView judgment(@PathVariable String reference,@Valid @RequestBody JudgmentInput input,HttpServletRequest request) {
        return claims.requestJudgment(reference,actor(request));
    }
    @PostMapping("/claims/{reference}/state")
    public ClaimView state(@PathVariable String reference,@Valid @RequestBody StateInput input,HttpServletRequest request) {
        return claims.transition(reference,input,actor(request));
    }
    @GetMapping("/claims/{reference}/events")
    public List<ClaimEvent> events(@PathVariable String reference) { return claims.events(reference); }
    @GetMapping("/claims/{reference}/notes")
    public List<CaseNote> notes(@PathVariable String reference) { return claims.notes(reference); }
    @PostMapping("/claims/{reference}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseNote note(@PathVariable String reference,@Valid @RequestBody NoteInput input,HttpServletRequest request) {
        return claims.addNote(reference,input,actor(request,"demo-caseworker"));
    }

    static String actor(HttpServletRequest request) {
        return actor(request,"demo-claimant");
    }

    private static String actor(HttpServletRequest request,String fallback) {
        String user=request.getHeader("X-Demo-User-Id");
        return user!=null && user.matches("[A-Za-z0-9_-]{1,100}") ? user : fallback;
    }
}
