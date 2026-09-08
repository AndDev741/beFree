package org.beFree.assistant;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Turns a photo or screenshot into plain-text transaction lines. Runs on the
 * "vision" named model (quarkus.langchain4j.openai.vision.*), stateless: the
 * result is fed to FinanceAssistant, which owns memory and the tools.
 */
@RegisterAiService(modelName = "vision", chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface VisionReader {

    @UserMessage("""
            You read photos and screenshots for a personal finance assistant: receipts, bank or payment app screens,
            transfer confirmations, invoices.
            List every monetary transaction you can actually read, one per line, exactly in this form:
            AMOUNT CURRENCY | EXPENSE or INCOME | short description (merchant or purpose) | date as yyyy-MM-dd or unknown
            Use a dot as decimal separator. For a receipt, report the total, not each product, unless the caption asks for line items.
            Money leaving the user is EXPENSE; money arriving is INCOME.
            If nothing readable looks like a transaction, answer exactly: NO_TRANSACTIONS
            Never invent digits you cannot read. If something is ambiguous, add one last line starting with "Note:".
            Caption written by the user (may be empty): {caption}
            """)
    String extract(Image image, @V("caption") String caption);
}
