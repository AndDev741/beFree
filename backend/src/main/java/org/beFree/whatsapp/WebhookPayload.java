package org.beFree.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Inbound webhook shape, reduced to what beFree reads. Unknown fields
 * (contacts, metadata, statuses) are ignored by Quarkus' Jackson defaults.
 */
public record WebhookPayload(String object, List<Entry> entry) {

    public record Entry(String id, List<Change> changes) {
    }

    public record Change(String field, Value value) {
    }

    public record Value(@JsonProperty("messaging_product") String messagingProduct,
                        List<InboundMessage> messages) {
    }

    public record InboundMessage(String from, String id, String timestamp, String type, Text text) {
    }

    public record Text(String body) {
    }
}
