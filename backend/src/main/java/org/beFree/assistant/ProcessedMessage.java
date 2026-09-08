package org.beFree.assistant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.beFree.transaction.Source;

import java.time.Instant;

/** Exactly-once ledger for inbound chat messages (providers redeliver on any non-2xx). */
@Entity
@Table(name = "inbound_messages")
public class ProcessedMessage extends PanacheEntityBase {

    @Id
    @Column(name = "external_id")
    public String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Source source;

    @Column(name = "received_at", nullable = false)
    public Instant receivedAt;
}
