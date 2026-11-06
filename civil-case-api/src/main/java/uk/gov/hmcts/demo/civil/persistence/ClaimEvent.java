package uk.gov.hmcts.demo.civil.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="claim_events",schema="civil")
public class ClaimEvent {
    @Id public UUID id;
    @JsonIgnore public UUID claimId;
    public String type;
    public String description;
    public String actor;
    public Instant createdAt;
}
