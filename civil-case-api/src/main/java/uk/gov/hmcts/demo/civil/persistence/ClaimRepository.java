package uk.gov.hmcts.demo.civil.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uk.gov.hmcts.demo.civil.domain.ClaimState;

public interface ClaimRepository extends JpaRepository<ClaimEntity, UUID>, JpaSpecificationExecutor<ClaimEntity> {
    Optional<ClaimEntity> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClaimEntity c where c.reference = :reference")
    Optional<ClaimEntity> lockByReference(@Param("reference") String reference);

    List<ClaimEntity> findByStateIn(List<ClaimState> states);
}
