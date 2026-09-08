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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Production processes on a virtual thread with no request context. The parser
 * fallback with a #tag does a Panache read outside any transaction, which is
 * exactly what blew up in prod on 2026-09-08 ("Cannot use the EntityManager").
 */
@QuarkusTest
@TestProfile(WhatsAppAsyncProcessingTest.AsyncOn.class)
class WhatsAppAsyncProcessingTest {

    public static class AsyncOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "whatsapp.async-processing", "true",
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

    @Test
    void backgroundThreadCanReadTheDatabase() throws Exception {
        when(assistant.chat(any(), any(), any())).thenThrow(new RuntimeException("force the parser path"));

        service.handle(new InboundMessage(WhatsAppFixtures.ME, "wamid.ASYNC1", WhatsAppFixtures.TS_2026_09_01,
                "text", new Text("4 café #Transport")));

        long deadline = System.currentTimeMillis() + 10_000;
        Optional<Transaction> stored;
        do {
            stored = Transaction.bySourceRef(Source.WHATSAPP, "wamid.ASYNC1");
            if (stored.isPresent()) break;
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);

        if (stored.isEmpty()) {
            fail("transaction was not recorded by the background thread within 10s");
        }
        assertEquals("café", stored.get().description);
    }
}
