package uk.gov.hmcts.demo.civil.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.demo.civil.web.ApiException;

@Repository
public class PostgresDocumentStore implements DocumentStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public PostgresDocumentStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Override
    public Metadata save(UUID claimId, String reference, String fileName, String type, byte[] content,
            String description, String kind) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
            insert into civil.documents(id,claim_id,file_name,content_type,size_bytes,content,scan_status,
                created_at,description,document_kind) values(?,?,?,?,?,?,'CLEAN',?,?,?)
            """, id, claimId, fileName, type, content.length, content, Timestamp.from(now), description, kind);
        return new Metadata(id, reference, fileName, type, content.length, "CLEAN", now);
    }

    @Override
    public List<Metadata> list(UUID claimId, String reference) {
        // Listing metadata never loads potentially large evidence bodies.
        return jdbc.query("""
            select id,file_name,content_type,size_bytes,scan_status,created_at from civil.documents
            where claim_id=? order by created_at,id
            """, (rs,row) -> metadata(rs,reference), claimId);
    }

    @Override
    public StoredDocument get(UUID claimId, String reference, UUID id) {
        return jdbc.query("select * from civil.documents where claim_id=? and id=?",
            (rs,row) -> new StoredDocument(metadata(rs,reference),rs.getBytes("content")), claimId,id)
            .stream().findFirst().orElseThrow(ApiException::missing);
    }

    @Override
    public boolean existsClaimForm(UUID claimId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from civil.documents where claim_id=? and document_kind='CLAIM_FORM')",
            Boolean.class, claimId));
    }

    @Override
    public void delete(UUID claimId, UUID id) {
        if (jdbc.update("delete from civil.documents where claim_id=? and id=?",claimId,id) == 0) {
            throw ApiException.missing();
        }
    }

    private Metadata metadata(ResultSet rs, String reference) throws SQLException {
        return new Metadata(rs.getObject("id",UUID.class), reference, rs.getString("file_name"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("scan_status"),
            rs.getTimestamp("created_at").toInstant());
    }
}
