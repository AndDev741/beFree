package org.beFree.telegram;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.beFree.telegram.TelegramApi.Update;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Long-polls getUpdates on a virtual thread. Outbound only: no webhook,
 * no public URL. Telegram allows one poller per token, so never run dev
 * and prod against the same bot.
 */
@ApplicationScoped
public class TelegramPoller {

    private static final Logger LOG = Logger.getLogger(TelegramPoller.class);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

    @Inject
    TelegramConfig config;

    @Inject
    @RestClient
    TelegramApi telegram;

    @Inject
    TelegramService service;

    private volatile boolean running;
    private Thread thread;

    void onStart(@Observes StartupEvent ev) {
        if (config.botToken().isEmpty()) {
            LOG.info("Telegram poller disabled: telegram.bot-token not set");
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("telegram-poller").start(this::loop);
        LOG.info("Telegram poller started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        String token = config.botToken().orElseThrow();
        Long offset = null;
        while (running) {
            try {
                var response = telegram.getUpdates(token, offset, config.pollTimeoutSeconds());
                if (response == null || !response.ok() || response.result() == null) {
                    sleep(ERROR_BACKOFF);
                    continue;
                }
                for (Update update : response.result()) {
                    // Confirm the update to Telegram even if handling fails,
                    // otherwise one poison message blocks the whole queue
                    offset = update.updateId() + 1;
                    try {
                        service.handle(update);
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to handle Telegram update %d", update.updateId());
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                LOG.warnf("Telegram poll failed (%s); retrying in %ds", e.getMessage(), ERROR_BACKOFF.toSeconds());
                sleep(ERROR_BACKOFF);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
