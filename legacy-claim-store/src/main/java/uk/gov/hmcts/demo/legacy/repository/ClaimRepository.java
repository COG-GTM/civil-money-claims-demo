package uk.gov.hmcts.demo.legacy.repository;

import java.util.List;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;

// The indexed columns remain authoritative when reading historical JSON blobs.
public interface ClaimRepository {
    String DATA = "CAST(CAST(claim_data AS jsonb) || jsonb_build_object('id', id, 'reference', reference, "
        + "'claimantId', claimant_id, 'state', state, 'securityCode', security_code, "
        + "'serviceDate', service_date, 'responseDeadline', response_deadline, "
        + "'createdAt', created_at, 'updatedAt', updated_at) AS text)";
    String FILTER = " WHERE (CAST(:state AS text) IS NULL OR state = :state) AND (CAST(:claimant AS text) IS NULL OR claimant_id = :claimant) "
        + "AND (CAST(:fromDate AS date) IS NULL OR (created_at AT TIME ZONE 'Europe/London')::date >= CAST(:fromDate AS date)) "
        + "AND (CAST(:toDate AS date) IS NULL OR (created_at AT TIME ZONE 'Europe/London')::date <= CAST(:toDate AS date))";

    @SqlQuery("SELECT " + DATA + " FROM legacy_cmc.claims WHERE reference = :reference FOR UPDATE")
    String findForUpdate(@Bind("reference") String reference);

    @SqlQuery("SELECT " + DATA + " FROM legacy_cmc.claims" + FILTER + " ORDER BY created_at DESC, reference DESC LIMIT :size OFFSET :offset")
    List<String> list(@Bind("state") String state, @Bind("claimant") String claimant,
        @Bind("fromDate") String from, @Bind("toDate") String to, @Bind("size") int size, @Bind("offset") long offset);

    @SqlQuery("SELECT count(*) FROM legacy_cmc.claims" + FILTER)
    long count(@Bind("state") String state, @Bind("claimant") String claimant,
        @Bind("fromDate") String from, @Bind("toDate") String to);

    @SqlQuery("SELECT count(*) FROM legacy_cmc.claims")
    long countAll();

    @SqlQuery("SELECT " + DATA + " FROM legacy_cmc.claims WHERE state IN ('ISSUED', 'AWAITING_RESPONSE') "
        + "AND response_deadline < CAST(:today AS date) ORDER BY reference FOR UPDATE")
    List<String> expired(@Bind("today") String today);

    @SqlUpdate("INSERT INTO legacy_cmc.claims(id,reference,claimant_id,state,claim_data,security_code,created_at,updated_at) "
        + "VALUES(CAST(:id AS uuid),:reference,:claimant,'DRAFT',:data,:code,CAST(:now AS timestamptz),CAST(:now AS timestamptz))")
    void insert(@Bind("id") String id, @Bind("reference") String reference, @Bind("claimant") String claimant,
        @Bind("data") String data, @Bind("code") String code, @Bind("now") String now);

    @SqlUpdate("UPDATE legacy_cmc.claims SET claim_data=:data, claimant_id=:claimant, state=:state, "
        + "service_date=CAST(:serviceDate AS date), response_deadline=CAST(:deadline AS date), "
        + "updated_at=CAST(:now AS timestamptz) WHERE reference=:reference")
    void update(@Bind("reference") String reference, @Bind("claimant") String claimant, @Bind("state") String state,
        @Bind("data") String data, @Bind("serviceDate") String serviceDate, @Bind("deadline") String deadline, @Bind("now") String now);

    @SqlQuery("SELECT to_char(date,'YYYY-MM-DD') FROM legacy_cmc.bank_holidays")
    List<String> bankHolidays();

    @SqlUpdate("INSERT INTO legacy_cmc.claim_events(id,claim_id,type,description,actor,created_at) "
        + "VALUES(CAST(:id AS uuid),CAST(:claimId AS uuid),:type,:description,:actor,CAST(:now AS timestamptz))")
    void event(@Bind("id") String id, @Bind("claimId") String claimId, @Bind("type") String type,
        @Bind("description") String description, @Bind("actor") String actor, @Bind("now") String now);

    @SqlQuery("SELECT CAST(json_build_object('id',id,'type',type,'description',description,'actor',actor,'createdAt',created_at) AS text) "
        + "FROM legacy_cmc.claim_events WHERE claim_id=CAST(:claimId AS uuid) ORDER BY created_at,id")
    List<String> events(@Bind("claimId") String claimId);

    @SqlQuery("SELECT CAST(json_build_object('id',id,'text',text,'author',author,'createdAt',created_at) AS text) "
        + "FROM legacy_cmc.case_notes WHERE claim_id=CAST(:claimId AS uuid) ORDER BY created_at,id")
    List<String> notes(@Bind("claimId") String claimId);

    @SqlUpdate("INSERT INTO legacy_cmc.case_notes(id,claim_id,text,author,created_at) "
        + "VALUES(CAST(:id AS uuid),CAST(:claimId AS uuid),:text,:author,CAST(:now AS timestamptz))")
    void note(@Bind("id") String id, @Bind("claimId") String claimId, @Bind("text") String text,
        @Bind("author") String author, @Bind("now") String now);

    @SqlQuery("SELECT CAST(json_build_object('id',id,'fileName',file_name,'contentType',content_type,'sizeBytes',size_bytes, "
        + "'scanStatus',scan_status,'createdAt',created_at) AS text) FROM legacy_cmc.documents "
        + "WHERE claim_id=CAST(:claimId AS uuid) ORDER BY created_at,id")
    List<String> documents(@Bind("claimId") String claimId);

    @SqlQuery("SELECT CAST(json_build_object('id',id,'fileName',file_name,'contentType',content_type,'sizeBytes',size_bytes, "
        + "'scanStatus',scan_status,'createdAt',created_at) AS text) FROM legacy_cmc.documents "
        + "WHERE claim_id=CAST(:claimId AS uuid) AND id=CAST(:id AS uuid)")
    String document(@Bind("claimId") String claimId, @Bind("id") String id);

    @SqlQuery("SELECT content FROM legacy_cmc.documents WHERE claim_id=CAST(:claimId AS uuid) AND id=CAST(:id AS uuid)")
    byte[] documentBody(@Bind("claimId") String claimId, @Bind("id") String id);

    @SqlUpdate("INSERT INTO legacy_cmc.documents(id,claim_id,file_name,content_type,size_bytes,content,scan_status,created_at) "
        + "VALUES(CAST(:id AS uuid),CAST(:claimId AS uuid),:name,:type,:size,:content,'CLEAN',CAST(:now AS timestamptz))")
    void document(@Bind("id") String id, @Bind("claimId") String claimId, @Bind("name") String name,
        @Bind("type") String type, @Bind("size") long size, @Bind("content") byte[] content, @Bind("now") String now);

    @SqlUpdate("DELETE FROM legacy_cmc.documents WHERE claim_id=CAST(:claimId AS uuid) AND id=CAST(:id AS uuid)")
    int deleteDocument(@Bind("claimId") String claimId, @Bind("id") String id);
}
