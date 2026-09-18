package org.beFree.transaction;
import org.beFree.category.Category;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "transactions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}),
       indexes = @Index(columnList = "occurredOn"))
public class Transaction extends PanacheEntity {

    // Always positive; direction lives in `type` so machine inputs (AI parser,
    // Open Banking mapper) can't silently flip a sign
    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public TransactionType type;

    @Column(nullable = false, length = 3)
    public String currency = "EUR";

    // When the money moved; createdAt below is when it entered the system
    @Column(nullable = false)
    public LocalDate occurredOn;

    public String description;

    @ManyToOne(fetch = FetchType.LAZY)
    public Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Source source;

    // Aggregator's transaction id; with `source` it is the idempotency key
    // for Open Banking ingestion (nulls stay distinct under the constraint)
    @Column(name = "external_id")
    public String externalId;

    // Original chat message (or media extraction), kept verbatim for debugging the assistant
    @Column(columnDefinition = "text")
    public String rawInput;

    /** Row owner. One user today; see CurrentUser for why the column exists now. */
    @Column(nullable = false, length = 64)
    public String owner;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    public Instant createdAt;

    /** Half-open on purpose: the month a date belongs to is not always its calendar month. */
    public static List<Transaction> between(LocalDate from, LocalDate to) {
        return list("occurredOn >= ?1 and occurredOn < ?2",
                Sort.by("occurredOn").and("id"),
                from, to);
    }

    public static Optional<Transaction> bySourceRef(Source source, String externalId) {
        return find("source = ?1 and externalId = ?2", source, externalId).firstResultOptional();
    }
}
