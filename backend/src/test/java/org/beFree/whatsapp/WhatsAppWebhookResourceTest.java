package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
@TestProfile(WhatsAppWebhookResourceTest.Configured.class)
class WhatsAppWebhookResourceTest {

    public static class Configured implements QuarkusTestProfile {
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

    private static final String ME = WhatsAppFixtures.ME;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    private static String payload(String from, String wamid, String text) {
        return WhatsAppFixtures.payload(from, wamid, text);
    }

    private static String sign(String body) {
        return WhatsAppFixtures.sign(body);
    }

    private static void post(String body, String signature) {
        var req = given().contentType(ContentType.JSON).body(body);
        if (signature != null) {
            req = req.header("X-Hub-Signature-256", signature);
        }
        req.when().post("/webhooks/whatsapp").then().statusCode(signature == null ? 401 : 200);
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
    void signedMessageIsRecordedAndAnswered() throws Exception {
        String body = payload(ME, "wamid.A1", "12,50 almoço");
        post(body, sign(body));

        var stored = Transaction.bySourceRef(Source.WHATSAPP, "wamid.A1").orElseThrow();
        assertEquals(new BigDecimal("12.50"), stored.amount);
        assertEquals("almoço", stored.description);
        assertEquals("12,50 almoço", stored.rawInput);
        assertEquals(LocalDate.of(2026, 9, 1), stored.occurredOn);

        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(eq("111"), eq("Bearer test-token"), reply.capture());
        assertEquals(ME, reply.getValue().to());
        assertTrue(reply.getValue().text().body().startsWith("✅ 12.50 EUR · almoço"));
    }

    @Test
    void badSignatureIsRejectedAndNothingStored() {
        String body = payload(ME, "wamid.B2", "9 café");
        given().contentType(ContentType.JSON).body(body)
                .header("X-Hub-Signature-256", "sha256=" + "00".repeat(32))
                .when().post("/webhooks/whatsapp")
                .then().statusCode(401);
        assertTrue(Transaction.bySourceRef(Source.WHATSAPP, "wamid.B2").isEmpty());
    }

    @Test
    void missingSignatureIsRejected() {
        post(payload(ME, "wamid.B3", "9 café"), null);
        assertTrue(Transaction.bySourceRef(Source.WHATSAPP, "wamid.B3").isEmpty());
    }

    @Test
    void unknownSenderIsAcknowledgedButIgnored() throws Exception {
        String body = payload("34600000000", "wamid.C3", "10 sneaky");
        post(body, sign(body));

        assertTrue(Transaction.bySourceRef(Source.WHATSAPP, "wamid.C3").isEmpty());
        verifyNoInteractions(whatsapp);
    }

    @Test
    void redeliveredMessageIsNotDuplicated() throws Exception {
        String body = payload(ME, "wamid.D4", "4 café");
        post(body, sign(body));
        post(body, sign(body));

        assertEquals(1, Transaction.count("source = ?1 and externalId = ?2", Source.WHATSAPP, "wamid.D4"));
        verify(whatsapp, org.mockito.Mockito.times(2)).sendMessage(any(), any(), any());
    }
}
