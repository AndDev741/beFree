package org.beFree.whatsapp;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.langchain4j.data.image.Image;
import org.beFree.assistant.ConversationContext;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.assistant.ProcessedMessage;
import org.beFree.assistant.VisionReader;
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
import java.util.Base64;
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

    @Inject
    VisionReader vision;

    @Inject
    WhatsAppMedia media;

    @Inject
    Transcriber transcriber;

    @Inject
    DocumentReader documents;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    Optional<String> llmApiKey;

    public void handle(InboundMessage message) {
        String from = message.from();
        if (from == null || message.id() == null) {
            return;
        }

        // Unknown senders get silence, not a reply: no hint that a bot lives here
        if (!isAllowed(from)) {
            LOG.warnf("Ignoring WhatsApp message from unauthorized number %s", from);
            return;
        }
        if (!claim(message.id())) {
            LOG.infof("WhatsApp message %s already processed; ignoring redelivery", message.id());
            return;
        }
        if (!isText(message) && !isImage(message) && !isAudio(message) && !isDocument(message)) {
            reply(from, "For now I understand text, images, voice messages and PDF documents.");
            return;
        }

        if (config.asyncProcessing()) {
            Thread.ofVirtual().name("whatsapp-" + message.id()).start(() -> withRequestContext(() -> process(message)));
        } else {
            withRequestContext(() -> process(message));
        }
    }

    /**
     * Hibernate needs a request context (or a transaction) on the calling thread.
     * The virtual thread we hand off to has neither, so give it one for the turn.
     */
    private static void withRequestContext(Runnable work) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            work.run();
            return;
        }
        requestContext.activate();
        try {
            work.run();
        } finally {
            requestContext.terminate();
        }
    }

    /** Runs the assistant (or the parser) and sends the reply. Package-private for tests. */
    void process(InboundMessage message) {
        String from = message.from();
        String reply;
        try {
            reply = route(message);
        } catch (Exception e) {
            // A turn that dies without a reply looks like silence to the user; never allow that
            LOG.errorf(e, "Unhandled failure processing WhatsApp message %s", message.id());
            reply = "Something went wrong on my side handling that message. Please try again.";
        }

        if (reply != null && !reply.isBlank()) {
            reply(from, reply);
        }
    }

    private String route(InboundMessage message) {
        String from = message.from();
        String reply;

        if (isImage(message)) {
            reply = processImage(message);
        } else if (isAudio(message)) {
            reply = processAudio(message);
        } else if (isDocument(message)) {
            reply = processDocument(message);
        } else if (assistantEnabled()) {
            String text = message.text().body();
            context.open(Source.WHATSAPP, message.id(), text);
            try {
                reply = assistant.chat(from, today(), text);
            } catch (Exception e) {
                LOG.warnf(e, "Assistant failed for message %s; falling back to the parser", message.id());
                reply = parseAndRecord(message);
            } finally {
                context.close();
            }
        } else {
            reply = parseAndRecord(message);
        }
        return reply;
    }

    /** Image → vision model → plain lines → the regular assistant, which records with the usual rules. */
    private String processImage(InboundMessage message) {
        if (!assistantEnabled()) {
            return "Reading images needs the AI assistant, which is not configured yet.";
        }
        String caption = message.image().caption() == null ? "" : message.image().caption().trim();

        String extraction;
        try {
            var file = media.download(message.image().id());
            Image image = Image.builder()
                    .base64Data(Base64.getEncoder().encodeToString(file.bytes()))
                    .mimeType(file.mimeType())
                    .build();
            extraction = vision.extract(image, caption);
        } catch (Exception e) {
            LOG.warnf(e, "Could not read image %s", message.id());
            return "I couldn't read that image. Try a clearer photo, or type the amount.";
        }
        if (extraction == null || extraction.isBlank()) {
            LOG.warnf("Vision model returned no text for image %s", message.id());
            return "I couldn't read anything useful in that image. Try a clearer photo, or type the amount.";
        }
        LOG.infof("Vision extraction for %s: %s", message.id(), extraction.replace('\n', '|'));

        String forAssistant = "[The user sent an image" + (caption.isEmpty() ? "" : " with the caption: \"" + caption + "\"") + "]\n"
                + "Extracted from the image:\n" + extraction;

        context.open(Source.WHATSAPP, message.id(), forAssistant);
        try {
            return assistant.chat(message.from(), today(), forAssistant);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed on image message %s", message.id());
            return "I read the image but couldn't record it right now. Here is what I saw:\n" + extraction;
        } finally {
            context.close();
        }
    }

    /** Voice note → transcription provider → the regular assistant. */
    private String processAudio(InboundMessage message) {
        if (!transcriber.enabled()) {
            return "Voice messages need a transcription provider, which is not configured yet. Type it instead for now.";
        }
        if (!assistantEnabled()) {
            return "Voice messages need the AI assistant, which is not configured yet.";
        }
        String transcript;
        try {
            var file = media.download(message.audio().id());
            transcript = transcriber.transcribe(file.bytes(), file.mimeType());
        } catch (Exception e) {
            LOG.warnf(e, "Could not transcribe audio %s", message.id());
            return "I couldn't understand that voice message. Try again or type it.";
        }
        if (transcript == null || transcript.isBlank()) {
            return "The voice message came through empty. Try again or type it.";
        }
        LOG.infof("Transcript for %s: %s", message.id(), transcript);

        String forAssistant = "[Voice message transcript]\n" + transcript.trim();
        context.open(Source.WHATSAPP, message.id(), forAssistant);
        try {
            return assistant.chat(message.from(), today(), forAssistant);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed on voice message %s", message.id());
            return "I heard: \"" + transcript.trim() + "\" but couldn't record it right now.";
        } finally {
            context.close();
        }
    }

    /** PDF → text → the assistant, which must summarise and ask before importing anything. */
    private String processDocument(InboundMessage message) {
        var doc = message.document();
        String name = doc.filename() == null ? "document" : doc.filename();
        boolean pdf = (doc.mimeType() != null && doc.mimeType().toLowerCase().startsWith("application/pdf"))
                || name.toLowerCase().endsWith(".pdf");
        if (!pdf) {
            return "I can only read PDF documents for now (this one is " + (doc.mimeType() == null ? "unknown" : doc.mimeType()) + ").";
        }
        if (!assistantEnabled()) {
            return "Reading documents needs the AI assistant, which is not configured yet.";
        }

        DocumentReader.Extracted extracted;
        try {
            var file = media.download(doc.id());
            extracted = documents.extract(file.bytes());
        } catch (Exception e) {
            LOG.warnf(e, "Could not read PDF %s (%s)", message.id(), name);
            return "I couldn't open that PDF. If it is password-protected, remove the password and send it again.";
        }
        if (extracted.isBlank()) {
            return "That PDF has no readable text (probably a scan). Send screenshots of the pages instead and I'll read those.";
        }
        LOG.infof("PDF %s: %s, %d pages, %d chars%s", message.id(), name, extracted.pages(),
                extracted.text().length(), extracted.truncated() ? " (truncated)" : "");

        String caption = doc.caption() == null ? "" : doc.caption().trim();
        String forAssistant = "[The user sent a PDF document \"" + name + "\" (" + extracted.pages() + " pages"
                + (extracted.truncated() ? ", text truncated" : "") + ")"
                + (caption.isEmpty() ? "" : " with the caption: \"" + caption + "\"") + "]\n"
                + "Extracted text:\n" + extracted.text();

        context.open(Source.WHATSAPP, message.id(), forAssistant);
        try {
            return assistant.chat(message.from(), today(), forAssistant);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed on document %s", message.id());
            return "I read the PDF (" + extracted.pages() + " pages) but couldn't process it right now. Please try again.";
        } finally {
            context.close();
        }
    }

    private String today() {
        return LocalDate.now(ZoneId.of(config.zone())).toString();
    }

    private static boolean isText(InboundMessage m) {
        return "text".equals(m.type()) && m.text() != null && m.text().body() != null;
    }

    private static boolean isImage(InboundMessage m) {
        return "image".equals(m.type()) && m.image() != null && m.image().id() != null;
    }

    private static boolean isAudio(InboundMessage m) {
        return "audio".equals(m.type()) && m.audio() != null && m.audio().id() != null;
    }

    private static boolean isDocument(InboundMessage m) {
        return "document".equals(m.type()) && m.document() != null && m.document().id() != null;
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
            whatsapp.sendMessage(phoneNumberId.get(), "Bearer " + token.get(), SendTextRequest.text(to, WhatsAppText.format(text)));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send WhatsApp reply to %s", to);
        }
    }
}
