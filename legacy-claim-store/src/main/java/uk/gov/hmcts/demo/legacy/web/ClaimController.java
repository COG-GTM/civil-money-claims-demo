package uk.gov.hmcts.demo.legacy.web;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.demo.legacy.service.ClaimService;

@RestController
@RequestMapping("/api")
public class ClaimController {
    private final ClaimService service;
    private final LegacyMetrics metrics;

    public ClaimController(ClaimService service, LegacyMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping("/fees/quote")
    public ObjectNode quote(@RequestBody ObjectNode input) { return service.quote(input); }

    @GetMapping("/claims")
    public ObjectNode list(@RequestParam(required = false) String state, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String claimantId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(state, from, to, claimantId, page, size);
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode create(@RequestBody ObjectNode input, HttpServletRequest request) { return service.create(input, actor(request, "demo-claimant")); }

    @PostMapping("/claims/lookup")
    public ObjectNode lookup(@RequestBody ObjectNode input) { return service.lookup(input); }

    @GetMapping("/claims/{reference}")
    public ObjectNode get(@PathVariable String reference) { return service.get(reference); }

    @PutMapping("/claims/{reference}")
    public ObjectNode update(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.update(reference, input, actor(request, "demo-claimant"));
    }

    @PostMapping("/claims/{reference}/issue")
    public ObjectNode issue(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.issue(reference, input, actor(request, "demo-claimant"));
    }

    @PostMapping("/claims/{reference}/acknowledge")
    public ObjectNode acknowledge(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.acknowledge(reference, actor(request, "demo-defendant"));
    }

    @PostMapping("/claims/{reference}/response")
    public ObjectNode response(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.respond(reference, input, actor(request, "demo-defendant"));
    }

    @PostMapping("/claims/{reference}/default-judgment")
    public ObjectNode judgment(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.defaultJudgment(reference, input, actor(request, "demo-claimant"));
    }

    @PostMapping("/claims/{reference}/state")
    public ObjectNode state(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        staff(request);
        return service.transition(reference, input, actor(request, "demo-caseworker"));
    }

    @GetMapping("/claims/{reference}/events")
    public ArrayNode events(@PathVariable String reference) { return service.events(reference); }

    @GetMapping("/claims/{reference}/notes")
    public ArrayNode notes(@PathVariable String reference) { return service.notes(reference); }

    @PostMapping("/claims/{reference}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode note(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        staff(request);
        return service.addNote(reference, input, actor(request, "demo-caseworker"));
    }

    @GetMapping("/claims/{reference}/documents")
    public ArrayNode documents(@PathVariable String reference) { return service.documents(reference); }

    @PostMapping(value = "/claims/{reference}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode upload(@PathVariable String reference, @RequestParam MultipartFile file,
            @RequestParam(required = false) String description, HttpServletRequest request) {
        return service.upload(reference, file, description, actor(request, "demo-claimant"));
    }

    @GetMapping("/claims/{reference}/documents/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable String reference, @PathVariable String documentId) {
        Map<String, Object> document = service.download(reference, documentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) document.get("contentType")))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename((String) document.get("fileName"), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff").body((byte[]) document.get("content"));
    }

    @DeleteMapping("/claims/{reference}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String reference, @PathVariable String documentId, HttpServletRequest request) {
        service.deleteDocument(reference, documentId, actor(request, "demo-claimant"));
    }

    @GetMapping("/claims/{reference}/offers")
    public ArrayNode offers(@PathVariable String reference) { return service.offers(reference); }

    @PostMapping("/claims/{reference}/offers")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode offer(@PathVariable String reference, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.createOffer(reference, input, actor(request, "demo-defendant"));
    }

    @PutMapping("/claims/{reference}/offers/{offerId}")
    public ObjectNode offer(@PathVariable String reference, @PathVariable String offerId, @RequestBody ObjectNode input, HttpServletRequest request) {
        return service.updateOffer(reference, offerId, input, actor(request, "demo-claimant"));
    }

    @GetMapping("/ops/metrics")
    public Map<String, Object> metrics() { return metrics.snapshot(); }

    @PostMapping("/ops/reset")
    public ObjectNode reset(@RequestHeader(value = "X-Demo-Reset-Token", required = false) String token, @RequestBody ObjectNode input) {
        return service.reset(token);
    }

    private String actor(HttpServletRequest request, String fallback) {
        String actor = request.getHeader("X-Demo-User-Id");
        return actor != null && actor.matches("[A-Za-z0-9_-]{1,100}") ? actor : fallback;
    }

    private void staff(HttpServletRequest request) {
        String role = request.getHeader("X-Demo-Role");
        if (role != null && !"caseworker".equals(role)) throw new LegacyApiException(403, "CASEWORKER_REQUIRED", "Choose the caseworker demo role");
    }
}
