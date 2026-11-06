package uk.gov.hmcts.demo.civil.document;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single source of truth for the evidence file types the service accepts: the detected MIME type,
 * the file extensions allowed for it and the human-readable label used in claim history.
 */
public enum EvidenceKind {
    PDF("application/pdf", "PDF document", ".pdf"),
    JPEG("image/jpeg", "JPEG image", ".jpg", ".jpeg"),
    PNG("image/png", "PNG image", ".png");

    private final String contentType;
    private final String label;
    private final List<String> extensions;

    EvidenceKind(String contentType, String label, String... extensions) {
        this.contentType = contentType;
        this.label = label;
        this.extensions = List.of(extensions);
    }

    public String contentType() { return contentType; }

    public String label() { return label; }

    /** True when the (already sanitised) file name ends with one of this kind's extensions. */
    public boolean matchesFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(lower::endsWith);
    }

    /** Looks up an accepted kind by MIME type; empty for anything the service does not accept. */
    public static Optional<EvidenceKind> fromContentType(String contentType) {
        return Arrays.stream(values()).filter(kind -> kind.contentType.equals(contentType)).findFirst();
    }
}
