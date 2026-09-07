package org.beFree.whatsapp;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.beFree.whatsapp.WhatsAppFixtures.payload;
import static org.beFree.whatsapp.WhatsAppFixtures.sign;
import static org.hamcrest.Matchers.is;

/**
 * Black-box run against the packaged app (native under -Dnative). Exists
 * because WebhookPayload is deserialized by hand and only a real binary can
 * prove its reflection registration; the reply to Graph API fails harmlessly
 * (fake token) and is logged, never surfaced to Meta.
 */
@QuarkusIntegrationTest
@TestProfile(WhatsAppWebhookIT.Configured.class)
class WhatsAppWebhookIT {

    public static class Configured implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "whatsapp.access-token", "test-token",
                    "whatsapp.phone-number-id", "111",
                    "whatsapp.verify-token", "verify-me",
                    "whatsapp.app-secret", WhatsAppFixtures.APP_SECRET,
                    "whatsapp.allowed-phones", ME);
        }
    }

    @Test
    void signedPayloadIsDeserializedAndRecordedByThePackagedApp() {
        String body = payload(ME, "wamid.IT1", "7,30 almoço it");

        given().contentType(ContentType.JSON)
                .header("X-Hub-Signature-256", sign(body))
                .body(body)
                .when().post("/webhooks/whatsapp")
                .then().statusCode(200);

        given().when().get("/transactions?month=2026-09")
                .then().statusCode(200)
                .body("find { it.source == 'WHATSAPP' && it.description == 'almoço it' }.amount", is(7.30f));
    }
}
