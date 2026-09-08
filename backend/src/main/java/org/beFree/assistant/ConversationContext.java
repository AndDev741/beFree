package org.beFree.assistant;

import jakarta.enterprise.context.ApplicationScoped;
import org.beFree.transaction.Source;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Carries the inbound message's identity to the tools the assistant calls,
 * on the thread running the chat turn. One message may yield several
 * transactions, so external ids are suffixed: wamid#1, wamid#2, ...
 */
@ApplicationScoped
public class ConversationContext {

    public record Scope(Source source, String externalId, String rawInput, AtomicInteger counter) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    public void open(Source source, String externalId, String rawInput) {
        CURRENT.set(new Scope(source, externalId, rawInput, new AtomicInteger()));
    }

    public void close() {
        CURRENT.remove();
    }

    public Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public String nextExternalId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.externalId() + "#" + scope.counter().incrementAndGet();
    }
}
