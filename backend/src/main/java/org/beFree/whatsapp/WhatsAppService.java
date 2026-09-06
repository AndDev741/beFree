package org.beFree.whatsapp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.category.Category;
import org.beFree.chat.MessageParser;
import org.beFree.transaction.NewTransaction;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionService;
import org.beFree.transaction.TransactionType;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Handles one inbound WhatsApp message: authorize the sender, parse, record, reply. */
@ApplicationScoped
public class WhatsAppService {

    private static final Logger LOG = Logger.getLogger(WhatsAppService.class);
    private static final String HELP = """
            I couldn't find an amount in that. Try:
            12,50 almoço
            3€ café #food
            +1500 salário""";

    @Inject
    WhatsAppConfig config;

    @Inject
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    TransactionService transactions;

    public void handle(InboundMessage message) {
        if (!"text".equals(message.type()) || message.text() == null) {
            return;
        }
        String from = message.from();

        // Unknown senders get silence, not a reply: no hint that a bot lives here
        if (!isAllowed(from)) {
            LOG.warnf("Ignoring WhatsApp message from unauthorized number %s", from);
            return;
        }

        String text = message.text().body();
        var parsed = MessageParser.parse(text);
        if (parsed.isEmpty()) {
            reply(from, HELP);
            return;
        }
        var p = parsed.get();

        Long categoryId = null;
        String categoryNote = "";
        if (p.categoryName() != null) {
            var category = Category.findByName(p.categoryName());
            if (category.isPresent()) {
                categoryId = category.get().id;
                categoryNote = " #" + category.get().name;
            } else {
                categoryNote = " (unknown category #" + p.categoryName() + ", left blank)";
            }
        }

        LocalDate occurredOn = Instant.ofEpochSecond(Long.parseLong(message.timestamp()))
                .atZone(ZoneId.of(config.zone()))
                .toLocalDate();

        Transaction t = transactions.record(new NewTransaction(
                p.amount(), p.type(), null, occurredOn, p.description(), categoryId,
                Source.WHATSAPP, message.id(), text));

        String sign = t.type == TransactionType.INCOME ? "+" : "";
        reply(from, "✅ %s%s %s · %s%s (#%d)".formatted(
                sign, t.amount.setScale(2, RoundingMode.HALF_UP), t.currency,
                t.description != null ? t.description : "no description",
                categoryNote, t.id));
    }

    private boolean isAllowed(String from) {
        List<String> allowed = config.allowedPhones().orElse(List.of());
        return from != null && allowed.contains(from);
    }

    private void reply(String to, String text) {
        var phoneNumberId = config.phoneNumberId();
        var token = config.accessToken();
        if (phoneNumberId.isEmpty() || token.isEmpty()) {
            LOG.warn("WhatsApp reply skipped: phone-number-id or access-token not configured");
            return;
        }
        try {
            whatsapp.sendMessage(phoneNumberId.get(), "Bearer " + token.get(), SendTextRequest.text(to, text));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send WhatsApp reply to %s", to);
        }
    }
}
