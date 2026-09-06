package org.beFree.telegram;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.telegram.TelegramApi.Chat;
import org.beFree.telegram.TelegramApi.Message;
import org.beFree.telegram.TelegramApi.SendMessageRequest;
import org.beFree.telegram.TelegramApi.Update;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(TelegramServiceTest.WithToken.class)
class TelegramServiceTest {

    // A token makes handle() work; the poller is never exercised because
    // the REST client is mocked and getUpdates returns null immediately.
    public static class WithToken implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("telegram.bot-token", "test-token");
        }
    }

    private static final long ALLOWED_CHAT = 42L;
    private static final long EPOCH_2026_09_01 = 1_788_264_000L; // 2026-09-01T12:00Z

    @InjectMock
    @RestClient
    TelegramApi telegram;

    @Inject
    TelegramService service;

    private static Update update(long updateId, long chatId, String text) {
        return new Update(updateId, new Message(EPOCH_2026_09_01, new Chat(chatId), text));
    }

    @Test
    void recordsTransactionAndReplies() {
        service.handle(update(1001, ALLOWED_CHAT, "12,50 almoço"));

        var stored = Transaction.bySourceRef(Source.TELEGRAM, "1001").orElseThrow();
        assertEquals(new BigDecimal("12.50"), stored.amount);
        assertEquals("almoço", stored.description);
        assertEquals("12,50 almoço", stored.rawInput);
        assertEquals(LocalDate.of(2026, 9, 1), stored.occurredOn);

        var reply = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(telegram).sendMessage(eq("test-token"), reply.capture());
        assertEquals(ALLOWED_CHAT, reply.getValue().chatId());
        assertTrue(reply.getValue().text().startsWith("✅ 12.50 EUR · almoço"));
    }

    @Test
    void redeliveredUpdateIsNotDuplicated() {
        service.handle(update(2002, ALLOWED_CHAT, "4 café"));
        service.handle(update(2002, ALLOWED_CHAT, "4 café"));

        assertEquals(1, Transaction.count("source = ?1 and externalId = ?2", Source.TELEGRAM, "2002"));
    }

    @Test
    void unauthorizedChatIsRejectedWithItsId() {
        service.handle(update(3003, 999L, "10 sneaky"));

        assertTrue(Transaction.bySourceRef(Source.TELEGRAM, "3003").isEmpty());
        var reply = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(telegram).sendMessage(eq("test-token"), reply.capture());
        assertTrue(reply.getValue().text().contains("999"));
    }

    @Test
    void unparseableMessageGetsHelp() {
        service.handle(update(4004, ALLOWED_CHAT, "bom dia"));

        assertTrue(Transaction.bySourceRef(Source.TELEGRAM, "4004").isEmpty());
        var reply = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(telegram).sendMessage(eq("test-token"), reply.capture());
        assertTrue(reply.getValue().text().contains("12,50 almoço"));
    }
}
