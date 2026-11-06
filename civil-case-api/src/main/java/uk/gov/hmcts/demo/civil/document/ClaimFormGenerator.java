package uk.gov.hmcts.demo.civil.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.demo.civil.persistence.ClaimEntity;

@Component
public class ClaimFormGenerator {
    public byte[] generate(ClaimEntity claim) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream text = new PDPageContentStream(pdf,page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                text.setLeading(22);
                text.newLineAtOffset(50,740);
                for (String line : new String[] {"HMCTS demo - Make a money claim",
                    "DEMO - this is not a real government service", "Claim reference: " + claim.reference,
                    "Total amount: GBP " + claim.totalAmount.toPlainString(),
                    "Response deadline: " + claim.responseDeadline,
                    "This generated claim form is a demonstration, not a court document."}) {
                    text.showText(line); text.newLine();
                }
                text.endText();
            }
            pdf.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not generate demo claim form",ex);
        }
    }
}
