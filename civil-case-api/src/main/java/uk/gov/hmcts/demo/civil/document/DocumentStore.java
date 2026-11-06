package uk.gov.hmcts.demo.civil.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentStore {
    record Metadata(UUID id, String claimReference, String fileName, String contentType,
            long sizeBytes, String scanStatus, Instant createdAt) {}
    record StoredDocument(Metadata metadata, byte[] content) {}
    Metadata save(UUID claimId, String reference, String fileName, String contentType, byte[] content,
            String description, String kind);
    List<Metadata> list(UUID claimId, String reference);
    StoredDocument get(UUID claimId, String reference, UUID documentId);
    boolean existsClaimForm(UUID claimId);
    void delete(UUID claimId, UUID documentId);
}
