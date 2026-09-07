package org.beFree.whatsapp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Shared between the @QuarkusTest and the packaged-mode IT. */
final class WhatsAppFixtures {

    static final String APP_SECRET = "test-secret";
    static final String ME = "351911111111";
    static final String TS_2026_09_01 = "1788264000";

    private WhatsAppFixtures() {
    }

    // Real Meta payloads carry more (metadata, contacts, internal fields); some is kept to prove it's ignored
    static String payload(String from, String wamid, String text) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"15550001111","phone_number_id":"111"},
                  "contacts":[{"profile":{"name":"And"},"wa_id":"%s","user_id":"PT.1","country_code":"PT"}],
                  "messages":[{"from":"%s","id":"%s","timestamp":"%s","type":"text","text":{"body":"%s"},
                               "internal_1p_only_data":{"account_context":{"account_context_type":"non_paid_messaging"}}}]
                }}]}]}
                """.formatted(from, from, wamid, TS_2026_09_01, text);
    }

    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
