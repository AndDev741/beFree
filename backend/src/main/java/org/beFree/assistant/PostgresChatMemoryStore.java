package org.beFree.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the extension's in-memory store so conversations survive pod
 * restarts and work with more than one replica (the stateless requirement).
 *
 * The JSON shape is hand-written with the Jackson tree API on purpose: the
 * library codec relies on reflection-driven type discriminators that the
 * native image dropped, producing rows it could not read back.
 */
@ApplicationScoped
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private static final Logger LOG = Logger.getLogger(PostgresChatMemoryStore.class);

    @Inject
    ObjectMapper mapper;

    @Override
    @Transactional
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemoryEntry entry = ChatMemoryEntry.findById(String.valueOf(memoryId));
        if (entry == null) {
            return new ArrayList<>();
        }
        try {
            return fromJson(entry.messages);
        } catch (Exception e) {
            // Self-heal: a row this code cannot read is worth less than a working chat
            LOG.warnf(e, "Unreadable chat memory for %s; starting a fresh conversation", memoryId);
            return new ArrayList<>();
        }
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
        entry.messages = toJson(messages);
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

    String toJson(List<ChatMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode node = array.addObject();
            switch (m) {
                case SystemMessage s -> node.put("type", "SYSTEM").put("text", s.text());
                case UserMessage u -> node.put("type", "USER").put("text", textOf(u));
                case AiMessage ai -> {
                    node.put("type", "AI");
                    if (ai.text() != null) {
                        node.put("text", ai.text());
                    }
                    if (ai.hasToolExecutionRequests()) {
                        ArrayNode calls = node.putArray("toolCalls");
                        for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                            calls.addObject().put("id", r.id()).put("name", r.name()).put("arguments", r.arguments());
                        }
                    }
                }
                case ToolExecutionResultMessage t -> node.put("type", "TOOL_RESULT")
                        .put("id", t.id()).put("toolName", t.toolName()).put("text", t.text());
                default -> LOG.warnf("Dropping unsupported chat message type %s from memory", m.getClass().getSimpleName());
            }
        }
        return array.toString();
    }

    List<ChatMessage> fromJson(String json) throws Exception {
        List<ChatMessage> out = new ArrayList<>();
        for (JsonNode node : mapper.readTree(json)) {
            String type = node.path("type").asText("");
            switch (type) {
                case "SYSTEM" -> out.add(SystemMessage.from(node.path("text").asText()));
                case "USER" -> out.add(UserMessage.from(node.path("text").asText()));
                case "AI" -> {
                    List<ToolExecutionRequest> calls = new ArrayList<>();
                    for (JsonNode c : node.path("toolCalls")) {
                        calls.add(ToolExecutionRequest.builder()
                                .id(c.path("id").asText(null))
                                .name(c.path("name").asText())
                                .arguments(c.path("arguments").asText("{}"))
                                .build());
                    }
                    var builder = AiMessage.builder().toolExecutionRequests(calls);
                    if (node.hasNonNull("text")) {
                        builder.text(node.get("text").asText());
                    }
                    out.add(builder.build());
                }
                case "TOOL_RESULT" -> out.add(ToolExecutionResultMessage.from(
                        node.path("id").asText(null), node.path("toolName").asText(), node.path("text").asText()));
                default -> throw new IllegalStateException("Unknown chat message type '" + type + "'");
            }
        }
        return out;
    }

    private static String textOf(UserMessage u) {
        StringBuilder sb = new StringBuilder();
        for (Content c : u.contents()) {
            if (c instanceof TextContent tc) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
