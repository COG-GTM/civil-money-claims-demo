package uk.gov.hmcts.demo.civil.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseNoteRepository extends JpaRepository<CaseNote, UUID> {
    List<CaseNote> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
