package org.beFree.loan;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money that came back. It keeps the currency it arrived in, which need not be
 * the loan's: only repayments in the loan's own currency count down what is
 * owed, the rest is shown beside it until the user closes the loan by hand.
 */
@Entity
@Table(name = "loan_repayments")
public class LoanRepayment extends PanacheEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    public Loan loan;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal amount;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(name = "occurred_on", nullable = false)
    public LocalDate occurredOn;

    public String note;

    /** Row owner. One user today; see CurrentUser for why the column exists now. */
    @Column(nullable = false, length = 64)
    public String owner;
}
