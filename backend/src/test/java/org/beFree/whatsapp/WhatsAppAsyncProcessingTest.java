package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.assistant.FinanceTools;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Text;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Production processes on a virtual thread with no request context. The mocked
 * model calls a real tool that reads the database, which is exactly what blew up
 * in prod on 2026-09-08 ("Cannot use the EntityManager/Session…").
 */
@QuarkusTest
@TestProfile(WhatsAppAsyncProcessingTest.AsyncOn.class)
class WhatsAppAsyncProcessingTest {

    public static class AsyncOn extends AssistantOnProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var map = new java.util.HashMap<>(super.getConfigOverrides());
            map.put("whatsapp.async-processing", "true");
            return map;
        }
    }

    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    FinanceTools tools;

    @Inject
    WhatsAppService service;

    @Test
    void backgroundThreadCanUseTheDatabaseThroughTools() throws Exception {
        CountDownLatch replied = new CountDownLatch(1);
        doAnswer(inv -> "Categories: " + tools.listCategories()).when(assistant).chat(any(), any(), any());
        doAnswer(inv -> { replied.countDown(); return null; }).when(whatsapp).sendMessage(any(), any(), any());

        service.handle(new InboundMessage(WhatsAppFixtures.ME, "wamid.ASYNC1", WhatsAppFixtures.TS_2026_09_01, "text", new Text("quais categorias tenho?")));

        assertTrue(replied.await(10, TimeUnit.SECONDS), "no reply was sent by the background thread within 10s");
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().startsWith("Categories: "), reply.getValue().text().body());
        assertEquals(WhatsAppFixtures.ME, reply.getValue().to());
    }
}
