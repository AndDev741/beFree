package org.beFree.media;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Text out of a PDF (statements, invoices). Scanned PDFs yield nothing here
 * and are reported as such; IBANs are redacted before any model sees the text.
 */
@ApplicationScoped
public class DocumentReader {

    /** Roughly 6k tokens: enough for a monthly statement, cheap enough to send. */
    static final int MAX_CHARS = 20_000;

    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,4})?\\b");

    public record Extracted(String text, int pages, boolean truncated) {
        public boolean isBlank() {
            return text == null || text.isBlank();
        }
    }

    public Extracted extract(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String cleaned = IBAN.matcher(text).replaceAll("[IBAN]")
                    .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
            boolean truncated = cleaned.length() > MAX_CHARS;
            return new Extracted(truncated ? cleaned.substring(0, MAX_CHARS) : cleaned,
                    document.getNumberOfPages(), truncated);
        }
    }
}
