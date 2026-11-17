package org.beFree.loan;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.beFree.transaction.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money lent to someone. What they still owe is not stored: it is the amount
 * minus the repayments made in the same currency, see {@link LoanService}.
 */
@Entity
@Table(name = "loans")
public class Loan extends PanacheEntity {

    /** Who has the money, written the way the user calls them ("Pai", "Richelle (mãe)"). */
    @Column(nullable = false)
    public String person;

    /** What it was for. */
    public String description;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    @Column(nullable = false, length = 3)
    public String currency = "EUR";

    @Column(name = "lent_on", nullable = false)
    public LocalDate lentOn;

    /**
     * The expense that paid it out, when it went through the ledger. A loan in
     * another currency, or one older than beFree, has none.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    public Transaction transaction;

    /**
     * Set when the user calls it done whatever the numbers say: paid back in
     * another currency, forgiven, or settled some other way.
     */
    @Column(name = "closed_on")
    public LocalDate closedOn;

    @Column(name = "closing_note")
    public String closingNote;

    /** Row owner. One user today; see CurrentUser for why the column exists now. */
    @Column(nullable = false, length = 64)
    public String owner;
}
