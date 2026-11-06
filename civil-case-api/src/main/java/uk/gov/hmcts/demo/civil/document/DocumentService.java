package uk.gov.hmcts.demo.civil.document;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.gov.hmcts.demo.civil.domain.ClaimState;
import uk.gov.hmcts.demo.civil.persistence.*;
import uk.gov.hmcts.demo.civil.web.ApiException;

@Service
public class DocumentService {
    private final ClaimRepository claims;
    private final ClaimEventRepository events;
    private final DocumentStore store;
    private final EvidenceValidator validator;
    private final VirusScanner scanner;
    private final Clock clock;
    public DocumentService(ClaimRepository claims, ClaimEventRepository events, DocumentStore store,
            EvidenceValidator validator, VirusScanner scanner, Clock clock) {
        this.claims=claims; this.events=events; this.store=store; this.validator=validator; this.scanner=scanner; this.clock=clock;
    }

    @Transactional
    public DocumentStore.Metadata upload(String reference, MultipartFile file, String description, String actor) {
        ClaimEntity claim=claims.lockByReference(reference.strip().toUpperCase(Locale.ROOT)).orElseThrow(ApiException::missing);
        if (claim.state==ClaimState.SETTLED || claim.state==ClaimState.TRANSFERRED) {
            throw ApiException.conflict("Evidence cannot be added to a closed claim");
        }
        if (description!=null && description.length()>2000) throw ApiException.invalid("description","Description must be 2000 characters or fewer");
        if (file.getSize()>EvidenceValidator.MAX_SIZE) throw new ApiException(413,"FILE_TOO_LARGE","The file must be 10 MB or smaller");
        try {
            byte[] content=file.getBytes();
            String name=validator.safeFileName(file.getOriginalFilename());
            String type=validator.validate(name,file.getContentType(),content);
            scanner.scan(content);
            DocumentStore.Metadata result=store.save(claim.id,claim.reference,name,type,content,description,"EVIDENCE");
            event(claim,"EVIDENCE_UPLOADED","Evidence uploaded",actor);
            return result;
        } catch(IOException ex) {
            throw new IllegalStateException("Could not read the uploaded evidence",ex);
        }
    }

    public List<DocumentStore.Metadata> list(String reference) {
        ClaimEntity claim=required(reference);
        return store.list(claim.id,claim.reference);
    }

    public DocumentStore.StoredDocument download(String reference, UUID id) {
        ClaimEntity claim=required(reference);
        return store.get(claim.id,claim.reference,id);
    }

    @Transactional
    public void delete(String reference, UUID id, String actor) {
        ClaimEntity claim=claims.lockByReference(reference.strip().toUpperCase(Locale.ROOT)).orElseThrow(ApiException::missing);
        if (claim.state!=ClaimState.DRAFT) throw ApiException.conflict("Only draft evidence can be deleted");
        store.delete(claim.id,id);
        event(claim,"EVIDENCE_DELETED","Draft evidence deleted",actor);
    }

    private ClaimEntity required(String reference) {
        return claims.findByReference(reference.strip().toUpperCase(Locale.ROOT)).orElseThrow(ApiException::missing);
    }

    private void event(ClaimEntity claim, String type, String description, String actor) {
        ClaimEvent event=new ClaimEvent();
        event.id=UUID.randomUUID(); event.claimId=claim.id; event.type=type; event.description=description;
        event.actor=actor; event.createdAt=clock.instant(); events.save(event);
    }
}
