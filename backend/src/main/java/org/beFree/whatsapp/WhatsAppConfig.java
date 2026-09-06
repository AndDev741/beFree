package org.beFree.whatsapp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/** WhatsApp Business Cloud API settings. All secrets arrive via environment. */
@ConfigMapping(prefix = "whatsapp")
public interface WhatsAppConfig {

    /** Permanent System User token used to send replies. */
    Optional<String> accessToken();

    /** The Meta "Phone number ID" of the sending number (not the phone number). */
    Optional<String> phoneNumberId();

    /** String we chose; Meta echoes it in the webhook verification handshake. */
    Optional<String> verifyToken();

    /** App secret; signs every webhook call (X-Hub-Signature-256). */
    Optional<String> appSecret();

    /** Senders allowed to log transactions, international format without "+". */
    Optional<List<String>> allowedPhones();

    @WithDefault("Europe/Lisbon")
    String zone();
}
