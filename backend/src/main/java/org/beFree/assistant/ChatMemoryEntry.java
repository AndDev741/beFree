package org.beFree.assistant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One conversation's message window, serialized by LangChain4j. */
@Entity
@Table(name = "chat_memory")
public class ChatMemoryEntry extends PanacheEntityBase {

    @Id
    @Column(name = "memory_id", length = 64)
    public String memoryId;

    @Column(columnDefinition = "text", nullable = false)
    public String messages;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
