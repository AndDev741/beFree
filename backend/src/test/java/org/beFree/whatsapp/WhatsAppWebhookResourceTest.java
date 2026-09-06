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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
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
                    "whatsapp.app-secret", "test-secret",
                    "whatsapp.allowed-phones", "351911111111");
        }
    }

    private static final String ME = "351911111111";
    private static final String TS_2026_09_01 = "1788264000";

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    // Real Meta payloads carry more (metadata, contacts, statuses); some is kept to prove it's ignored
    private static String payload(String from, String wamid, String text) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"15550001111","phone_number_id":"111"},
                  "contacts":[{"profile":{"name":"And"},"wa_id":"%s"}],
                  "messages":[{"from":"%s","id":"%s","timestamp":"%s","type":"text","text":{"body":"%s"}}]
                }}]}]}
                """.formatted(from, from, wamid, TS_2026_09_01, text);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
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
