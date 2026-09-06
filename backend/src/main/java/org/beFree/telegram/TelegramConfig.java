package org.beFree.telegram;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "telegram")
public interface TelegramConfig {

    /** BotFather token. Absent = poller disabled. */
    Optional<String> botToken();

    /** Chats allowed to log transactions. Absent/empty = nobody (safe default). */
    Optional<List<Long>> allowedChatIds();

    @WithDefault("30")
    int pollTimeoutSeconds();

    /** Zone used to turn a message's epoch timestamp into occurredOn. */
    @WithDefault("Europe/Lisbon")
    String zone();
}
