package org.beFree.whatsapp;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.assistant.AssistantService;
import org.beFree.auth.CurrentUser;
import org.beFree.media.MediaIngest;
import org.beFree.transaction.Source;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Media;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * One inbound WhatsApp message, end to end: authorize the sender, claim the
 * message id exactly once, turn media into text, hand the text to the
 * assistant, send its reply. Every outcome ends in a reply; silence is a bug.
 */
@ApplicationScoped
public class WhatsAppService {

    private static final Logger LOG = Logger.getLogger(WhatsAppService.class);

    static final String NOT_CONFIGURED = "O assistente ainda não está configurado, por isso não registei nada.";
    static final String TRY_AGAIN = "Não consegui processar isso agora. Tenta daqui a bocado.";
    // The rest of the wording is shared with the in-app chat
    static final String UNSUPPORTED = MediaIngest.UNSUPPORTED;
    static final String IMAGE_UNREADABLE = MediaIngest.IMAGE_UNREADABLE;
    static final String AUDIO_NOT_CONFIGURED = MediaIngest.AUDIO_NOT_CONFIGURED;
    static final String AUDIO_UNREADABLE = MediaIngest.AUDIO_UNREADABLE;
    static final String PDF_UNREADABLE = MediaIngest.PDF_UNREADABLE;
    static final String PDF_NO_TEXT = MediaIngest.PDF_NO_TEXT;

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
    CurrentUser currentUser;

    @Inject
    MediaIngest ingest;

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
            return assistant.chat(currentUser.name(), Source.WHATSAPP, message.id(), userText);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed for message %s", message.id());
            return TRY_AGAIN;
        }
    }

    /** Photo or screenshot → vision model → transaction lines → the assistant. */
    private String image(InboundMessage message) {
        Media image = message.image();
        MediaIngest.Result read;
        try {
            var file = media.download(image.id());
            read = ingest.image(file.bytes(), file.mimeType(), caption(image), message.id());
        } catch (Exception e) {
            LOG.warnf(e, "Could not download image %s", message.id());
            return IMAGE_UNREADABLE;
        }
        return converse(message, read);
    }

    /** Voice note → transcription → the assistant. */
    private String audio(InboundMessage message) {
        if (!ingest.canTranscribe()) {
            return AUDIO_NOT_CONFIGURED;
        }
        MediaIngest.Result read;
        try {
            var file = media.download(message.audio().id());
            read = ingest.audio(file.bytes(), file.mimeType(), message.id());
        } catch (Exception e) {
            LOG.warnf(e, "Could not download audio %s", message.id());
            return AUDIO_UNREADABLE;
        }
        return converse(message, read);
    }

    /** PDF → text → the assistant, which summarises and asks before importing. */
    private String document(InboundMessage message) {
        Media doc = message.document();
        String name = doc.filename() == null ? "document" : doc.filename();
        if (ingest.kindOf(doc.mimeType(), name) != MediaIngest.Kind.DOCUMENT) {
            return "Por agora só leio PDFs (este é " + (doc.mimeType() == null ? "de tipo desconhecido" : doc.mimeType()) + ").";
        }
        MediaIngest.Result read;
        try {
            var file = media.download(doc.id());
            read = ingest.document(file.bytes(), name, caption(doc), message.id());
        } catch (Exception e) {
            LOG.warnf(e, "Could not download PDF %s (%s)", message.id(), name);
            return PDF_UNREADABLE;
        }
        return converse(message, read);
    }

    private String converse(InboundMessage message, MediaIngest.Result read) {
        return switch (read) {
            case MediaIngest.Result.Framed(String text) -> converse(message, text);
            case MediaIngest.Result.Rejected(String reason) -> reason;
        };
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
