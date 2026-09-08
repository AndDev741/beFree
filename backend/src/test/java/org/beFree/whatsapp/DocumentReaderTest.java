package org.beFree.whatsapp;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit test: no Quarkus needed to read a PDF. */
class DocumentReaderTest {

    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.beginText();
                cs.newLineAtOffset(40, 750);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextAndRedactsIbans() throws Exception {
        byte[] bytes = pdf("EXTRATO Conta PT50 0002 0123 1234 5678 9015 4",
                "01-09-2026 LIDL -50,00",
                "02-09-2026 ORDENADO +1500,00");

        var out = new DocumentReader().extract(bytes);

        assertEquals(1, out.pages());
        assertFalse(out.truncated());
        assertTrue(out.text().contains("LIDL -50,00"), out.text());
        assertTrue(out.text().contains("[IBAN]"), out.text());
        assertFalse(out.text().contains("0002 0123"), out.text());
    }

    @Test
    void emptyPageIsBlank() throws Exception {
        assertTrue(new DocumentReader().extract(pdf()).isBlank());
    }
}
