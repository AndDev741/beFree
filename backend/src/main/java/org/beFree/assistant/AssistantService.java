package org.beFree.assistant;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.beFree.transaction.Source;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Runs one conversational turn: opens the conversation scope the tools read
 * (source, external id, raw input), tells the model today's date, and closes
 * the scope. Channels call this and only this.
 */
@ApplicationScoped
public class AssistantService {

    private static final Logger LOG = Logger.getLogger(AssistantService.class);

    /** Sentinel kept in application.properties because the extension requires the key to exist. */
    static final String DISABLED = "disabled";

    @Inject
    FinanceAssistant assistant;

    @Inject
    ConversationContext context;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    void onStart(@Observes StartupEvent event) {
        if (!enabled()) {
            LOG.warn("AI assistant disabled: quarkus.langchain4j.openai.api-key is not set. Chat messages get a configuration notice.");
        }
    }

    public boolean enabled() {
        return apiKey.filter(k -> !k.isBlank() && !DISABLED.equalsIgnoreCase(k.trim())).isPresent();
    }

    /**
     * @param memoryId   conversation key (the sender's phone number)
     * @param externalId provider message id; tools derive idempotency keys from it
     * @param userText   what the model sees as the user's message (text, or framed media content)
     * @return the model's reply, to be sent back verbatim (after channel formatting)
     */
    public String chat(String memoryId, Source source, String externalId, String userText) {
        context.open(source, externalId, userText);
        try {
            return assistant.chat(memoryId, LocalDate.now(ZoneId.of(zone)).toString(), userText);
        } finally {
            context.close();
        }
    }
}
