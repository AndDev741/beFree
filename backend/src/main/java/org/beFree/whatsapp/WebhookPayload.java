package org.beFree.whatsapp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Inbound webhook shape, reduced to what beFree reads. Unknown fields
 * (contacts, metadata, statuses) are ignored by Quarkus' Jackson defaults.
 *
 * Deserialized by hand from the raw body (the HMAC check needs the exact
 * bytes), so it never appears in a resource signature and Quarkus cannot
 * discover it: every record here must be registered for reflection or the
 * native binary fails with "no delegate- or property-based Creator".
 */
@RegisterForReflection
public record WebhookPayload(String object, List<Entry> entry) {

    @RegisterForReflection
    public record Entry(String id, List<Change> changes) {
    }

    @RegisterForReflection
    public record Change(String field, Value value) {
    }

    @RegisterForReflection
    public record Value(@JsonProperty("messaging_product") String messagingProduct,
                        List<InboundMessage> messages) {
    }

    /** type is "text", "image", "document", "audio", ...; only the matching field is set. */
    @RegisterForReflection
    public record InboundMessage(String from, String id, String timestamp, String type,
                                 Text text, Media image, Media document, Media audio) {

        @JsonCreator
        public InboundMessage {
        }

        public InboundMessage(String from, String id, String timestamp, String type, Text text) {
            this(from, id, timestamp, type, text, null, null, null);
        }
    }

    @RegisterForReflection
    public record Text(String body) {
    }

    @RegisterForReflection
    public record Media(String id, @JsonProperty("mime_type") String mimeType, String caption, String filename) {
    }
}
