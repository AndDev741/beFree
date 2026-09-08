package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.assistant.VisionReader;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Media;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.beFree.whatsapp.WhatsAppMedia.Downloaded;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Vision model, assistant and media download are mocked; this covers the wiring between them. */
@QuarkusTest
@TestProfile(WhatsAppImageFlowTest.AssistantOn.class)
class WhatsAppImageFlowTest {

    public static class AssistantOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.langchain4j.openai.api-key", "test-key",
                    "whatsapp.access-token", "test-token",
                    "whatsapp.phone-number-id", "111",
                    "whatsapp.verify-token", "verify-me",
                    "whatsapp.app-secret", WhatsAppFixtures.APP_SECRET,
                    "whatsapp.allowed-phones", ME);
        }
    }

    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    VisionReader vision;

    @InjectMock
    WhatsAppMedia media;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Test
    void imageGoesThroughVisionThenTheAssistant() throws Exception {
        when(media.download("media-1")).thenReturn(new Downloaded("jpegbytes".getBytes(StandardCharsets.UTF_8), "image/jpeg"));
        when(vision.extract(any(), eq("almoço de hoje"))).thenReturn("12.50 EUR | EXPENSE | Restaurante Tia Alice | 2026-09-01");
        when(assistant.chat(eq(ME), anyString(), anyString())).thenReturn("✅ #7 12.50 EUR Restaurante Tia Alice");

        String body = WhatsAppFixtures.imagePayload(ME, "wamid.IMG1", "media-1", "almoço de hoje");
        given().contentType(ContentType.JSON).header("X-Hub-Signature-256", WhatsAppFixtures.sign(body)).body(body)
                .when().post("/webhooks/whatsapp").then().statusCode(200);

        var toAssistant = ArgumentCaptor.forClass(String.class);
        verify(assistant).chat(eq(ME), anyString(), toAssistant.capture());
        assertTrue(toAssistant.getValue().startsWith("[The user sent an image with the caption: \"almoço de hoje\"]"), toAssistant.getValue());
        assertTrue(toAssistant.getValue().contains("12.50 EUR | EXPENSE | Restaurante Tia Alice"));

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(eq("111"), eq("Bearer test-token"), reply.capture());
        assertEquals("✅ #7 12.50 EUR Restaurante Tia Alice", reply.getValue().text().body());
    }

    @Test
    void unreadableImageGetsAnHonestReplyAndNoAssistantCall() throws Exception {
        when(media.download("media-2")).thenThrow(new java.io.IOException("media download failed: HTTP 404"));

        String body = WhatsAppFixtures.imagePayload(ME, "wamid.IMG2", "media-2", null);
        given().contentType(ContentType.JSON).header("X-Hub-Signature-256", WhatsAppFixtures.sign(body)).body(body)
                .when().post("/webhooks/whatsapp").then().statusCode(200);

        verifyNoInteractions(assistant, vision);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().startsWith("I couldn't read that image"));
    }

    @Test
    void emptyVisionResultGetsAnHonestReply() throws Exception {
        when(media.download("media-3")).thenReturn(new Downloaded(new byte[]{1}, "image/jpeg"));
        when(vision.extract(any(), anyString())).thenReturn(null);

        String body = WhatsAppFixtures.imagePayload(ME, "wamid.IMG3", "media-3", null);
        given().contentType(ContentType.JSON).header("X-Hub-Signature-256", WhatsAppFixtures.sign(body)).body(body)
                .when().post("/webhooks/whatsapp").then().statusCode(200);

        verifyNoInteractions(assistant);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().startsWith("I couldn't read anything useful"));
    }

    @Test
    void unsupportedTypesAreAnsweredNotIgnored() {
        var doc = new InboundMessage(ME, "wamid.DOC1", WhatsAppFixtures.TS_2026_09_01, "document",
                null, null, new Media("m", "application/pdf", null, "extrato.pdf"), null);
        new WhatsAppServiceAccessor().handle(doc);

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().contains("PDFs are coming"));
        verifyNoInteractions(assistant, vision, media);
    }

    /** Tiny indirection so the test can call the service without an @Inject field per test class. */
    static class WhatsAppServiceAccessor {
        void handle(InboundMessage m) {
            io.quarkus.arc.Arc.container().instance(WhatsAppService.class).get().handle(m);
        }
    }
}
