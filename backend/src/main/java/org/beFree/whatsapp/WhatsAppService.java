package org.beFree.whatsapp;

import dev.langchain4j.data.image.Image;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.assistant.AssistantService;
import org.beFree.assistant.VisionReader;
import org.beFree.media.DocumentReader;
import org.beFree.media.Transcriber;
import org.beFree.transaction.Source;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Media;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * One inbound WhatsApp message, end to end: authorize the sender, claim the
 * message id exactly once, turn media into text, hand the text to the
 * assistant, send its reply. Every outcome ends in a reply; silence is a bug.
 */
@ApplicationScoped
public class WhatsAppService {

    private static final Logger LOG = Logger.getLogger(WhatsAppService.class);

    static final String NOT_CONFIGURED = "The assistant is not configured yet, so I couldn't record that.";
    static final String TRY_AGAIN = "I couldn't process that right now. Please try again in a moment.";
    static final String UNSUPPORTED = "I understand text, images, voice messages and PDF documents.";
    static final String IMAGE_UNREADABLE = "I couldn't read that image. Try a clearer photo, or type the amount.";
    static final String AUDIO_NOT_CONFIGURED = "Voice messages need a transcription provider, which is not configured yet. Type it instead for now.";
    static final String AUDIO_UNREADABLE = "I couldn't understand that voice message. Try again or type it.";
    static final String PDF_UNREADABLE = "I couldn't open that PDF. If it is password-protected, remove the password and send it again.";
    static final String PDF_NO_TEXT = "That PDF has no readable text (probably a scan). Send screenshots of the pages instead and I'll read those.";

    @Inject
    WhatsAppConfig config;

    @Inject
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppMedia media;

    @Inject
    AssistantService assistant;

    @Inject
    VisionReader vision;

    @Inject
    Transcriber transcriber;

    @Inject
    DocumentReader documents;

    public void handle(InboundMessage message) {
        if (message.from() == null || message.id() == null) {
            return;
        }
        // Unknown senders get silence, not a reply: no hint that a bot lives here
        if (!isAllowed(message.from())) {
            LOG.warnf("Ignoring WhatsApp message from unauthorized number %s", message.from());
            return;
        }
        if (!claim(message.id())) {
            LOG.infof("WhatsApp message %s already processed; ignoring redelivery", message.id());
            return;
        }
        if (Kind.of(message) == Kind.OTHER) {
            send(message.from(), UNSUPPORTED);
            return;
        }
        if (config.asyncProcessing()) {
            Thread.ofVirtual().name("whatsapp-" + message.id()).start(() -> withRequestContext(() -> process(message)));
        } else {
            withRequestContext(() -> process(message));
        }
    }

    /** Package-private so tests can drive a turn synchronously. */
    void process(InboundMessage message) {
        String reply;
        try {
            reply = route(message);
        } catch (Exception e) {
            LOG.errorf(e, "Unhandled failure processing WhatsApp message %s", message.id());
            reply = TRY_AGAIN;
        }
        send(message.from(), reply);
    }

    private String route(InboundMessage message) {
        if (!assistant.enabled()) {
            return NOT_CONFIGURED;
        }
        return switch (Kind.of(message)) {
            case TEXT -> converse(message, message.text().body());
            case IMAGE -> image(message);
            case AUDIO -> audio(message);
            case DOCUMENT -> document(message);
            case OTHER -> UNSUPPORTED;
        };
    }

    private String converse(InboundMessage message, String userText) {
        try {
            return assistant.chat(message.from(), Source.WHATSAPP, message.id(), userText);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed for message %s", message.id());
            return TRY_AGAIN;
        }
    }

    /** Photo or screenshot → vision model → transaction lines → the assistant. */
    private String image(InboundMessage message) {
        Media image = message.image();
        String caption = caption(image);
        String extraction;
        try {
            var file = media.download(image.id());
            extraction = vision.extract(Image.builder()
                    .base64Data(Base64.getEncoder().encodeToString(file.bytes()))
                    .mimeType(file.mimeType())
                    .build(), caption);
        } catch (Exception e) {
            LOG.warnf(e, "Could not read image %s", message.id());
            return IMAGE_UNREADABLE;
        }
        if (extraction == null || extraction.isBlank()) {
            LOG.warnf("Vision model returned no text for image %s", message.id());
            return IMAGE_UNREADABLE;
        }
        LOG.infof("Vision extraction for %s: %s", message.id(), extraction.replace('\n', '|'));

        String framed = "[The user sent an image" + (caption.isEmpty() ? "" : " with the caption: \"" + caption + "\"") + "]\n"
                + "Extracted from the image:\n" + extraction;
        return converse(message, framed);
    }

    /** Voice note → transcription → the assistant. */
    private String audio(InboundMessage message) {
        if (!transcriber.enabled()) {
            return AUDIO_NOT_CONFIGURED;
        }
        String transcript;
        try {
            var file = media.download(message.audio().id());
            transcript = transcriber.transcribe(file.bytes(), file.mimeType());
        } catch (Exception e) {
            LOG.warnf(e, "Could not transcribe audio %s", message.id());
            return AUDIO_UNREADABLE;
        }
        if (transcript == null || transcript.isBlank()) {
            return AUDIO_UNREADABLE;
        }
        LOG.infof("Transcript for %s: %s", message.id(), transcript);
        return converse(message, "[Voice message transcript]\n" + transcript.trim());
    }

    /** PDF → text → the assistant, which summarises and asks before importing. */
    private String document(InboundMessage message) {
        Media doc = message.document();
        String name = doc.filename() == null ? "document" : doc.filename();
        boolean pdf = (doc.mimeType() != null && doc.mimeType().toLowerCase().startsWith("application/pdf"))
                || name.toLowerCase().endsWith(".pdf");
        if (!pdf) {
            return "I can only read PDF documents for now (this one is " + (doc.mimeType() == null ? "unknown" : doc.mimeType()) + ").";
        }
        DocumentReader.Extracted extracted;
        try {
            var file = media.download(doc.id());
            extracted = documents.extract(file.bytes());
        } catch (Exception e) {
            LOG.warnf(e, "Could not read PDF %s (%s)", message.id(), name);
            return PDF_UNREADABLE;
        }
        if (extracted.isBlank()) {
            return PDF_NO_TEXT;
        }
        LOG.infof("PDF %s: %s, %d pages, %d chars%s", message.id(), name, extracted.pages(),
                extracted.text().length(), extracted.truncated() ? " (truncated)" : "");

        String caption = caption(doc);
        String framed = "[The user sent a PDF document \"" + name + "\" (" + extracted.pages() + " pages"
                + (extracted.truncated() ? ", text truncated" : "") + ")"
                + (caption.isEmpty() ? "" : " with the caption: \"" + caption + "\"") + "]\n"
                + "Extracted text:\n" + extracted.text();
        return converse(message, framed);
    }

    private static String caption(Media media) {
        return media.caption() == null ? "" : media.caption().trim();
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

    private boolean isAllowed(String from) {
        List<String> allowed = config.allowedPhones().orElse(List.of());
        return allowed.contains(from);
    }

    private void send(String to, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
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

    /** Hibernate needs a request context (or a transaction) on the calling thread; the virtual thread has neither. */
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

    enum Kind {
        TEXT, IMAGE, AUDIO, DOCUMENT, OTHER;

        static Kind of(InboundMessage m) {
            if ("text".equals(m.type()) && m.text() != null && m.text().body() != null) return TEXT;
            if ("image".equals(m.type()) && m.image() != null && m.image().id() != null) return IMAGE;
            if ("audio".equals(m.type()) && m.audio() != null && m.audio().id() != null) return AUDIO;
            if ("document".equals(m.type()) && m.document() != null && m.document().id() != null) return DOCUMENT;
            return OTHER;
        }
    }
}
