package uk.gov.hmcts.demo.civil.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.demo.civil.document.*;

@RestController
@RequestMapping("/api/claims/{reference}/documents")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents=documents; }
    @GetMapping
    public List<DocumentStore.Metadata> list(@PathVariable String reference) { return documents.list(reference); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentStore.Metadata upload(@PathVariable String reference,@RequestPart("file") MultipartFile file,
            @RequestParam(required=false) String description,HttpServletRequest request) {
        return documents.upload(reference,file,description,ClaimController.actor(request));
    }
    @GetMapping("/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable String reference,@PathVariable UUID documentId) {
        DocumentStore.StoredDocument document=documents.download(reference,documentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(document.metadata().contentType()))
            .contentLength(document.metadata().sizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                .filename(document.metadata().fileName(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(document.content());
    }
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String reference,@PathVariable UUID documentId,HttpServletRequest request) {
        documents.delete(reference,documentId,ClaimController.actor(request));
    }
}
