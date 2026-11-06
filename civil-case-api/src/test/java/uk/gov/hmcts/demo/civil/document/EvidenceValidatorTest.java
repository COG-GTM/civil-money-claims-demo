package uk.gov.hmcts.demo.civil.document;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.demo.civil.web.ApiException;

class EvidenceValidatorTest {
    private final EvidenceValidator validator=new EvidenceValidator();
    private final byte[] pdf="%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    @Test void checksMagicMimeAndExtensionTogether() {
        assertThat(validator.validate("proof.PDF","application/pdf",pdf)).isEqualTo("application/pdf");
        assertThat(validator.validate("proof.jpg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255,0})).isEqualTo("image/jpeg");
        assertThat(validator.validate("proof.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10})).isEqualTo("image/png");
        assertThatThrownBy(() -> validator.validate("proof.jpg","image/jpeg",pdf)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("proof.pdf","text/plain",pdf)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("proof.pdf","application/pdf","hello".getBytes())).isInstanceOf(ApiException.class);
    }
    // Every accepted kind must resolve back from its MIME type and carry a history label.
    @Test void everyAcceptedKindHasLabelAndLookup() {
        for (EvidenceKind kind : EvidenceKind.values()) {
            assertThat(EvidenceKind.fromContentType(kind.contentType())).contains(kind);
            assertThat(kind.label()).isNotBlank();
        }
        assertThat(EvidenceKind.fromContentType("text/plain")).isEmpty();
    }
    @Test void acceptsExactlyTenMibRejectsLargerAndEmpty() {
        byte[] allowed=new byte[EvidenceValidator.MAX_SIZE];
        System.arraycopy(pdf,0,allowed,0,pdf.length);
        assertThat(validator.validate("proof.pdf","application/pdf",allowed)).isEqualTo("application/pdf");
        assertThatThrownBy(() -> validator.validate("proof.pdf","application/pdf",new byte[allowed.length+1]))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(413));
        assertThatThrownBy(() -> validator.validate("proof.pdf","application/pdf",new byte[0]))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status).isEqualTo(400));
    }
    @Test void stripsPathsControlsAndHeaderInjection() {
        assertThat(validator.safeFileName("../../secret.pdf")).isEqualTo("secret.pdf");
        assertThat(validator.safeFileName("C:\\folder\\proof.pdf")).isEqualTo("proof.pdf");
        assertThat(validator.safeFileName("proof\r\nX-Header: value.pdf")).doesNotContain("\r","\n",":");
        assertThat(validator.safeFileName("a".repeat(300)+".pdf")).hasSize(200).endsWith(".pdf");
    }
}
