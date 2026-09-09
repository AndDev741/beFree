package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.restassured.RestAssured.given;
import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.beFree.whatsapp.WhatsAppFixtures.payload;
import static org.beFree.whatsapp.WhatsAppFixtures.sign;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The HTTP edge: Meta's handshake, signatures, sender allowlist, exactly-once. */
@QuarkusTest
@TestProfile(AssistantOnProfile.class)
class WhatsAppWebhookResourceTest {

    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    private static void post(String body, String signature, int expectedStatus) {
        var request = given().contentType(ContentType.JSON).body(body);
        if (signature != null) {
            request = request.header("X-Hub-Signature-256", signature);
        }
        request.when().post("/webhooks/whatsapp").then().statusCode(expectedStatus);
    }

    @Test
    void verificationHandshakeEchoesChallenge() {
        given().queryParam("hub.mode", "subscribe")
                .queryParam("hub.verify_token", "verify-me")
                .queryParam("hub.challenge", "1234567890")
                .when().get("/webhooks/whatsapp")
                .then().statusCode(200).body(is("1234567890"));
    }

    @Test
    void verificationWithWrongTokenIsForbidden() {
        given().queryParam("hub.mode", "subscribe")
                .queryParam("hub.verify_token", "nope")
                .queryParam("hub.challenge", "1234567890")
                .when().get("/webhooks/whatsapp")
                .then().statusCode(403);
    }

    @Test
    void signedTextReachesTheAssistantAndTheReplyGoesBack() {
        when(assistant.chat(eq(ME), anyString(), eq("12,50 almoço"))).thenReturn("✅ #1 12.50 EUR almoço");

        String body = payload(ME, "wamid.A1", "12,50 almoço");
        post(body, sign(body), 200);

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(eq("111"), eq("Bearer test-token"), reply.capture());
        assertEquals(ME, reply.getValue().to());
        assertEquals("✅ #1 12.50 EUR almoço", reply.getValue().text().body());
    }

    @Test
    void badSignatureIsRejected() {
        post(payload(ME, "wamid.B2", "9 café"), "sha256=" + "00".repeat(32), 401);
        verifyNoInteractions(assistant, whatsapp);
    }

    @Test
    void missingSignatureIsRejected() {
        post(payload(ME, "wamid.B3", "9 café"), null, 401);
        verifyNoInteractions(assistant, whatsapp);
    }

    @Test
    void unknownSenderIsAcknowledgedButIgnored() {
        String body = payload("34600000000", "wamid.C3", "10 sneaky");
        post(body, sign(body), 200);
        verifyNoInteractions(assistant, whatsapp);
    }

    @Test
    void redeliveredMessageIsHandledOnce() {
        when(assistant.chat(any(), any(), any())).thenReturn("ok");

        String body = payload(ME, "wamid.D4", "4 café");
        post(body, sign(body), 200);
        post(body, sign(body), 200);

        verify(assistant, times(1)).chat(any(), any(), any());
        verify(whatsapp, times(1)).sendMessage(any(), any(), any());
    }
}
