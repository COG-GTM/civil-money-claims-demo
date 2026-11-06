package uk.gov.hmcts.demo.legacy.repository;

import java.math.BigDecimal;
import java.util.List;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;

public interface OffersRepository {
    String DATA = "CAST(CAST(offer_data AS jsonb) || jsonb_build_object('id',id,'amount',amount,'status',status,'createdAt',created_at) AS text)";

    @SqlQuery("SELECT " + DATA + " FROM legacy_cmc.offers WHERE claim_id=CAST(:claimId AS uuid) ORDER BY created_at,id")
    List<String> list(@Bind("claimId") String claimId);

    @SqlQuery("SELECT " + DATA + " FROM legacy_cmc.offers WHERE claim_id=CAST(:claimId AS uuid) AND id=CAST(:id AS uuid) FOR UPDATE")
    String find(@Bind("claimId") String claimId, @Bind("id") String id);

    @SqlUpdate("INSERT INTO legacy_cmc.offers(id,claim_id,amount,status,offer_data,created_at) "
        + "VALUES(CAST(:id AS uuid),CAST(:claimId AS uuid),:amount,'PENDING',:data,CAST(:now AS timestamptz))")
    void insert(@Bind("id") String id, @Bind("claimId") String claimId, @Bind("amount") BigDecimal amount,
        @Bind("data") String data, @Bind("now") String now);

    @SqlUpdate("UPDATE legacy_cmc.offers SET status=:status,offer_data=:data WHERE id=CAST(:id AS uuid) AND claim_id=CAST(:claimId AS uuid)")
    void update(@Bind("id") String id, @Bind("claimId") String claimId, @Bind("status") String status, @Bind("data") String data);
}
