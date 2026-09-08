package org.beFree.assistant;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the extension's in-memory store so conversations survive pod
 * restarts and work with more than one replica (the stateless requirement).
 */
@ApplicationScoped
public class PostgresChatMemoryStore implements ChatMemoryStore {

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemoryEntry entry = ChatMemoryEntry.findById(String.valueOf(memoryId));
        if (entry == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(entry.messages));
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        ChatMemoryEntry entry = ChatMemoryEntry.findById(id);
        boolean isNew = entry == null;
        if (isNew) {
            entry = new ChatMemoryEntry();
            entry.memoryId = id;
        }
        entry.messages = ChatMessageSerializer.messagesToJson(messages);
        entry.updatedAt = Instant.now();
        if (isNew) {
            entry.persist();
        }
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        ChatMemoryEntry.deleteById(String.valueOf(memoryId));
    }
}
