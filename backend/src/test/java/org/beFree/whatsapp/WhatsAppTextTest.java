package org.beFree.whatsapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhatsAppTextTest {

    @Test
    void translatesMarkdownHabitsToWhatsAppFormatting() {
        String in = """
                ## Resumo
                **Aproximadamente 49 transações**, por exemplo:
                - 08/09 Zahir Kebab 5,90
                - 07/09 Uber 10,89

                ```
                total: 1.234,56
                ```


                Queres que **importe** tudo?""";
        String expected = """
                *Resumo*
                *Aproximadamente 49 transações*, por exemplo:
                • 08/09 Zahir Kebab 5,90
                • 07/09 Uber 10,89

                total: 1.234,56

                Queres que *importe* tudo?""";
        assertEquals(expected, WhatsAppText.format(in));
    }

    @Test
    void leavesPlainRepliesAlone() {
        assertEquals("✅ #52 3.00 EUR café (Food)", WhatsAppText.format("✅ #52 3.00 EUR café (Food)"));
        assertEquals("12,50 * 2 = 25", WhatsAppText.format("12,50 * 2 = 25"));
        assertNull(WhatsAppText.format(null));
    }
}
