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

/**
 * Black-box run against the packaged app (the native binary under -Dnative).
 * WebhookPayload is deserialized by hand, so only a real binary proves its
 * reflection registration: a missing @RegisterForReflection turns this 200
 * into a 500 (seen in prod on 2026-09-07). Processing itself is covered by
 * the @QuarkusTest classes with the model mocked.
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
    void realShapedPayloadIsAcceptedByThePackagedApp() {
        String body = payload(ME, "wamid.IT1", "7,30 almoço it");
        given().contentType(ContentType.JSON)
                .header("X-Hub-Signature-256", sign(body))
                .body(body)
                .when().post("/webhooks/whatsapp")
                .then().statusCode(200);

        given().queryParam("hub.mode", "subscribe").queryParam("hub.verify_token", "verify-me").queryParam("hub.challenge", "42")
                .when().get("/webhooks/whatsapp")
                .then().statusCode(200);
    }
}
