package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Text;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AssistantOnProfile.class)
class WhatsAppServiceTest {

    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppService service;

    private static InboundMessage text(String wamid, String body) {
        return new InboundMessage(ME, wamid, WhatsAppFixtures.TS_2026_09_01, "text", new Text(body));
    }

    private String replySentFor(InboundMessage message) {
        service.handle(message);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        return reply.getValue().text().body();
    }

    @Test
    void modelFailureAsksToRetryInsteadOfGuessing() {
        when(assistant.chat(any(), any(), any())).thenThrow(new RuntimeException("model down"));
        assertEquals(WhatsAppService.TRY_AGAIN, replySentFor(text("wamid.S1", "4 café")));
    }

    @Test
    void unsupportedTypesGetAnAnswerWithoutTouchingTheModel() {
        var sticker = new InboundMessage(ME, "wamid.S2", WhatsAppFixtures.TS_2026_09_01, "sticker", null, null, null, null);
        assertEquals(WhatsAppService.UNSUPPORTED, replySentFor(sticker));
        verifyNoInteractions(assistant);
    }

    @Test
    void repliesAreFormattedForWhatsApp() {
        when(assistant.chat(any(), any(), any())).thenReturn("**Resumo**\n- item");
        assertEquals("*Resumo*\n• item", replySentFor(text("wamid.S3", "resumo")));
    }
}
