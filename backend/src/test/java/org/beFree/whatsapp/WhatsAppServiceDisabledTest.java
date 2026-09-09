package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Text;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/** No model key (the sentinel stays): the bot says so instead of pretending. */
@QuarkusTest
@TestProfile(WhatsAppServiceDisabledTest.NoKey.class)
class WhatsAppServiceDisabledTest {

    public static class NoKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "whatsapp.access-token", "test-token",
                    "whatsapp.phone-number-id", "111",
                    "whatsapp.verify-token", "verify-me",
                    "whatsapp.app-secret", WhatsAppFixtures.APP_SECRET,
                    "whatsapp.allowed-phones", WhatsAppFixtures.ME);
        }
    }

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppService service;

    @Test
    void textIsAnsweredWithAConfigurationNotice() {
        service.handle(new InboundMessage(WhatsAppFixtures.ME, "wamid.OFF1", WhatsAppFixtures.TS_2026_09_01, "text", new Text("4 café")));

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertEquals(WhatsAppService.NOT_CONFIGURED, reply.getValue().text().body());
    }
}
