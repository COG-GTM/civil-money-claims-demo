package uk.gov.hmcts.demo.civil.document;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.demo.civil.web.ApiException;

@Component
public class EvidenceValidator {
    public static final int MAX_SIZE = 10 * 1024 * 1024;

    public String validate(String fileName, String suppliedType, byte[] content) {
        if (content.length > MAX_SIZE) throw new ApiException(413, "FILE_TOO_LARGE", "The file must be 10 MB or smaller");
        if (content.length == 0) throw ApiException.invalid("file", "Choose a file that is not empty");
        String actual = detect(content);
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean extensionMatches = switch (actual) {
            case "application/pdf" -> lower.endsWith(".pdf");
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            default -> false;
        };
        if (!extensionMatches || !actual.equals(suppliedType)) {
            throw new ApiException(415,"UNSUPPORTED_FILE_TYPE","Upload a PDF, JPG or PNG with matching file contents");
        }
        return actual;
    }

    private String detect(byte[] bytes) {
        if (bytes.length >= 5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (bytes.length >= 3 && (bytes[0]&255) == 255 && (bytes[1]&255) == 216 && (bytes[2]&255) == 255) return "image/jpeg";
        byte[] png = {(byte)137,80,78,71,13,10,26,10};
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes,8),png)) return "image/png";
        return "";
    }

    public String safeFileName(String original) {
        String base = original == null ? "evidence" : original.replace('\\','/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "evidence";
        if (base.length() > 200) {
            int dot = base.lastIndexOf('.');
            String extension = dot > 0 && base.length() - dot <= 6 ? base.substring(dot) : "";
            base = base.substring(0,200-extension.length()) + extension;
        }
        return base;
    }
}
