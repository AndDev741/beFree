package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Text;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The assistant is mocked: this covers the wiring around it, not the model. */
@QuarkusTest
@TestProfile(WhatsAppAssistantFlowTest.AssistantOn.class)
class WhatsAppAssistantFlowTest {

    public static class AssistantOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.langchain4j.openai.api-key", "test-key",
                    "whatsapp.access-token", "test-token",
                    "whatsapp.phone-number-id", "111",
                    "whatsapp.verify-token", "verify-me",
                    "whatsapp.app-secret", WhatsAppFixtures.APP_SECRET,
                    "whatsapp.allowed-phones", WhatsAppFixtures.ME);
        }
    }

    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppService service;

    private static InboundMessage inbound(String wamid, String body) {
        return new InboundMessage(WhatsAppFixtures.ME, wamid, WhatsAppFixtures.TS_2026_09_01, "text", new Text(body));
    }

    @Test
    void assistantReplyIsForwardedToTheUser() {
        when(assistant.chat(eq(WhatsAppFixtures.ME), anyString(), eq("quanto gastei este mês?")))
                .thenReturn("Gastaste 15,50 EUR este mês.");

        service.handle(inbound("wamid.FLOW1", "quanto gastei este mês?"));

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(eq("111"), eq("Bearer test-token"), reply.capture());
        assertEquals("Gastaste 15,50 EUR este mês.", reply.getValue().text().body());
    }

    @Test
    void assistantFailureFallsBackToTheParser() {
        when(assistant.chat(any(), any(), any())).thenThrow(new RuntimeException("model down"));

        service.handle(inbound("wamid.FLOW2", "4 café"));

        var stored = Transaction.bySourceRef(Source.WHATSAPP, "wamid.FLOW2").orElseThrow();
        assertEquals("café", stored.description);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().startsWith("✅ 4.00 EUR · café"));
    }

    @Test
    void redeliveryIsProcessedOnce() {
        when(assistant.chat(any(), any(), any())).thenReturn("ok");

        service.handle(inbound("wamid.FLOW3", "3 café"));
        service.handle(inbound("wamid.FLOW3", "3 café"));

        verify(assistant, times(1)).chat(any(), any(), any());
        verify(whatsapp, times(1)).sendMessage(any(), any(), any());
    }
}
