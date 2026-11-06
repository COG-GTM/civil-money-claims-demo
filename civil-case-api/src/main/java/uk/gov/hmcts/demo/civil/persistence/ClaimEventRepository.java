package uk.gov.hmcts.demo.civil.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimEventRepository extends JpaRepository<ClaimEvent, UUID> {
    List<ClaimEvent> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
