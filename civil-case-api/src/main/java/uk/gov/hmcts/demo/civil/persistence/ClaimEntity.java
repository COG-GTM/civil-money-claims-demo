package uk.gov.hmcts.demo.civil.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.hmcts.demo.civil.domain.ClaimState;
import uk.gov.hmcts.demo.civil.web.ClaimModels.*;

@Entity
@Table(name="claims", schema="civil")
public class ClaimEntity {
    @Id public UUID id;
    @Column(nullable=false,unique=true) public String reference;
    public String claimantId = "demo-claimant";
    @Enumerated(EnumType.STRING) public ClaimState state = ClaimState.DRAFT;
    @JdbcTypeCode(SqlTypes.JSON) public Party claimant = new Party(null,null,null,null,null);
    @JdbcTypeCode(SqlTypes.JSON) public Party defendant = new Party(null,null,null,null,null);
    @JdbcTypeCode(SqlTypes.JSON) public List<AmountItem> amountBreakdown = new ArrayList<>();
    public BigDecimal principalAmount = BigDecimal.ZERO;
    @JdbcTypeCode(SqlTypes.JSON) public Interest interest = Interest.none();
    public BigDecimal interestAmount = BigDecimal.ZERO;
    public BigDecimal dailyInterest = BigDecimal.ZERO;
    public BigDecimal fee = BigDecimal.ZERO;
    public BigDecimal totalAmount = BigDecimal.ZERO;
    public String reason = "";
    @JdbcTypeCode(SqlTypes.JSON) public List<TimelineEntry> timeline = new ArrayList<>();
    public String securityCode;
    public boolean acknowledged;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant issuedAt;
    public LocalDate serviceDate;
    public LocalDate responseDeadline;
    @JdbcTypeCode(SqlTypes.JSON) public DefendantResponse response;
    public Instant judgmentRequestedAt;
    @Version public Long version;
}
