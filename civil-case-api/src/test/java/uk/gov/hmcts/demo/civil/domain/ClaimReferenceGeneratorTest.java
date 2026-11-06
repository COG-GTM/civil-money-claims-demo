package uk.gov.hmcts.demo.civil.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ClaimReferenceGeneratorTest {
    @Test void padsThreeDigitsAndExpandsWithoutChangingPrefix() {
        assertThat(ClaimReferenceGenerator.format(1)).isEqualTo("000MC001");
        assertThat(ClaimReferenceGenerator.format(999)).isEqualTo("000MC999");
        assertThat(ClaimReferenceGenerator.format(1000)).isEqualTo("000MC1000");
    }
    @Test void asksTheDatabaseForEveryReference() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select nextval('civil.claim_reference_seq')",Long.class)).thenReturn(161L,162L);
        ClaimReferenceGenerator generator=new ClaimReferenceGenerator(jdbc);
        assertThat(generator.generate()).isEqualTo("000MC161");
        assertThat(generator.generate()).isEqualTo("000MC162");
        verify(jdbc,times(2)).queryForObject("select nextval('civil.claim_reference_seq')",Long.class);
    }
    @Test void rejectsInvalidSequenceNumbers() {
        assertThatThrownBy(() -> ClaimReferenceGenerator.format(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
