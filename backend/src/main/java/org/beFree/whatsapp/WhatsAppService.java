package org.beFree.whatsapp;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.assistant.ConversationContext;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.assistant.ProcessedMessage;
import org.beFree.category.Category;
import org.beFree.chat.MessageParser;
import org.beFree.transaction.NewTransaction;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionService;
import org.beFree.transaction.TransactionType;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Inbound WhatsApp message: authorize the sender, claim the message id once,
 * then let the assistant handle it (regex parser as fallback), and reply.
 */
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

    @Inject
    FinanceAssistant assistant;

    @Inject
    ConversationContext context;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    Optional<String> llmApiKey;

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
        if (!claim(message.id())) {
            LOG.infof("WhatsApp message %s already processed; ignoring redelivery", message.id());
            return;
        }

        if (config.asyncProcessing()) {
            Thread.ofVirtual().name("whatsapp-" + message.id()).start(() -> process(message));
        } else {
            process(message);
        }
    }

    /** Runs the assistant (or the parser) and sends the reply. Package-private for tests. */
    void process(InboundMessage message) {
        String from = message.from();
        String text = message.text().body();
        String reply;

        if (assistantEnabled()) {
            context.open(Source.WHATSAPP, message.id(), text);
            try {
                reply = assistant.chat(from, LocalDate.now(ZoneId.of(config.zone())).toString(), text);
            } catch (Exception e) {
                LOG.warnf(e, "Assistant failed for message %s; falling back to the parser", message.id());
                reply = parseAndRecord(message);
            } finally {
                context.close();
            }
        } else {
            reply = parseAndRecord(message);
        }

        if (reply != null && !reply.isBlank()) {
            reply(from, reply);
        }
    }

    private boolean assistantEnabled() {
        return llmApiKey.filter(k -> !k.isBlank() && !"disabled".equalsIgnoreCase(k.trim())).isPresent();
    }

    /** Insert-or-skip on the message id; the second of two racing deliveries hits the primary key. */
    private boolean claim(String wamid) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                if (ProcessedMessage.findById(wamid) != null) {
                    return false;
                }
                ProcessedMessage p = new ProcessedMessage();
                p.externalId = wamid;
                p.source = Source.WHATSAPP;
                p.receivedAt = Instant.now();
                p.persist();
                return true;
            });
        } catch (Exception e) {
            LOG.debugf(e, "Claim failed for %s; treating as duplicate", wamid);
            return false;
        }
    }

    /** The pre-assistant behaviour, kept as the no-key / outage fallback. */
    private String parseAndRecord(InboundMessage message) {
        String text = message.text().body();
        var parsed = MessageParser.parse(text);
        if (parsed.isEmpty()) {
            return HELP;
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
        LOG.infof("Recorded WhatsApp transaction #%d (%s %s) from %s via parser", t.id, t.amount, t.currency, message.from());

        String sign = t.type == TransactionType.INCOME ? "+" : "";
        return "✅ %s%s %s · %s%s (#%d)".formatted(
                sign, t.amount.setScale(2, RoundingMode.HALF_UP), t.currency,
                t.description != null ? t.description : "no description",
                categoryNote, t.id);
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
