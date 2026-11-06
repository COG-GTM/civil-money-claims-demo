package uk.gov.hmcts.demo.civil.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="case_notes",schema="civil")
public class CaseNote {
    @Id public UUID id;
    @JsonIgnore public UUID claimId;
    public String text;
    public String author;
    public Instant createdAt;
}
