package org.beFree.assistant;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PostgresChatMemoryStoreTest {

    @Inject
    PostgresChatMemoryStore store;

    @Test
    void roundTripsAWindow() {
        store.updateMessages("351900000001", List.of(
                SystemMessage.from("you are beFree"),
                UserMessage.from("3 café"),
                AiMessage.from("✅ #1 3.00 EUR café")));

        List<ChatMessage> back = store.getMessages("351900000001");
        assertEquals(3, back.size());
        assertTrue(back.get(1) instanceof UserMessage);

        store.updateMessages("351900000001", List.of(UserMessage.from("only this")));
        assertEquals(1, store.getMessages("351900000001").size());

        store.deleteMessages("351900000001");
        assertTrue(store.getMessages("351900000001").isEmpty());
    }
}
