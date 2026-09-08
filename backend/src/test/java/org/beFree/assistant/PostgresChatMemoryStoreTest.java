package org.beFree.assistant;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PostgresChatMemoryStoreTest {

    @Inject
    PostgresChatMemoryStore store;

    @Test
    void roundTripsAFullToolCallingTurn() {
        var call = ToolExecutionRequest.builder().id("call_1").name("listCategories").arguments("{}").build();
        List<ChatMessage> turn = List.of(
                SystemMessage.from("you are beFree"),
                UserMessage.from("3 café"),
                AiMessage.builder().toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call_1", "listCategories", "Food, Transport"),
                AiMessage.from("✅ #1 3.00 EUR café (Food)"));

        store.updateMessages("351900000001", turn);
        List<ChatMessage> back = store.getMessages("351900000001");

        assertEquals(5, back.size());
        assertEquals("3 café", ((UserMessage) back.get(1)).singleText());
        AiMessage ai = (AiMessage) back.get(2);
        assertTrue(ai.hasToolExecutionRequests());
        assertEquals("listCategories", ai.toolExecutionRequests().get(0).name());
        assertEquals("call_1", ai.toolExecutionRequests().get(0).id());
        assertEquals("Food, Transport", ((ToolExecutionResultMessage) back.get(3)).text());
        assertEquals("✅ #1 3.00 EUR café (Food)", ((AiMessage) back.get(4)).text());

        store.updateMessages("351900000001", List.of(UserMessage.from("only this")));
        assertEquals(1, store.getMessages("351900000001").size());

        store.deleteMessages("351900000001");
        assertTrue(store.getMessages("351900000001").isEmpty());
    }

    @Test
    @Transactional
    void unreadableRowStartsAFreshConversation() {
        ChatMemoryEntry broken = new ChatMemoryEntry();
        broken.memoryId = "351900000002";
        broken.messages = "[{\"text\":\"written by the old codec, no type field\"}]";
        broken.updatedAt = Instant.now();
        broken.persist();

        assertTrue(store.getMessages("351900000002").isEmpty());
    }
}
