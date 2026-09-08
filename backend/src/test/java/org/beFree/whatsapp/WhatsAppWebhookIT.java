package org.beFree.whatsapp;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.beFree.whatsapp.WhatsAppFixtures.payload;
import static org.beFree.whatsapp.WhatsAppFixtures.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box run against the packaged app (native under -Dnative). Exists
 * because WebhookPayload is deserialized by hand and only a real binary can
 * prove its reflection registration. The packaged app runs the prod profile,
 * so it acks first and records on a virtual thread: the check has to poll.
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
    void signedPayloadIsDeserializedAndRecordedByThePackagedApp() throws InterruptedException {
        String body = payload(ME, "wamid.IT1", "7,30 almoço it");

        given().contentType(ContentType.JSON)
                .header("X-Hub-Signature-256", sign(body))
                .body(body)
                .when().post("/webhooks/whatsapp")
                .then().statusCode(200);

        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            List<Float> amounts = given().when().get("/transactions?month=2026-09")
                    .then().statusCode(200)
                    .extract().jsonPath()
                    .getList("findAll { it.source == 'WHATSAPP' && it.description == 'almoço it' }.amount", Float.class);
            if (!amounts.isEmpty()) {
                assertEquals(7.30f, amounts.get(0), 0.001f);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("WhatsApp transaction was not recorded within 15s of the webhook ack");
            }
            Thread.sleep(300);
        }
    }
}
