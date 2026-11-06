package uk.gov.hmcts.demo.legacy.repository;

import org.skife.jdbi.v2.sqlobject.SqlQuery;

public interface ReferenceNumberRepository {
    @SqlQuery("SELECT nextval('legacy_cmc.claim_reference_seq')")
    long nextNumber();
}
