package org.beFree.whatsapp;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** WhatsApp fully configured and the assistant switched on (the model itself is mocked per test). */
public class AssistantOnProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.langchain4j.openai.api-key", "test-key",
                "whatsapp.access-token", "test-token",
                "whatsapp.phone-number-id", "111",
                "whatsapp.verify-token", "verify-me",
                "whatsapp.app-secret", WhatsAppFixtures.APP_SECRET,
                "whatsapp.allowed-phones", WhatsAppFixtures.ME);
    }
}
