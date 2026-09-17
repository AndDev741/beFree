package org.beFree.budget;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.beFree.category.Category;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * How much may be spent in one category during one month ("allocation").
 *
 * Only the limit is stored. What has been spent is a sum over transactions,
 * so editing, deleting or recategorising a transaction can never leave a
 * stale running total behind.
 */
@Entity
@Table(name = "budgets", uniqueConstraints = @UniqueConstraint(columnNames = {"category_id", "period"}))
public class Budget extends PanacheEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    public Category category;

    /** First day of the month this budget applies to; YearMonth has no JDBC type. */
    @Column(nullable = false)
    public LocalDate period;

    @Column(name = "limit_amount", nullable = false, precision = 12, scale = 2)
    public BigDecimal limitAmount;

    @Column(nullable = false, length = 3)
    public String currency = "EUR";

    /** Row owner. One user today; see CurrentUser for why the column exists now. */
    @Column(nullable = false, length = 64)
    public String owner;

    public YearMonth month() {
        return YearMonth.from(period);
    }

    public static Optional<Budget> find(Category category, YearMonth month) {
        return find("category = ?1 and period = ?2", category, month.atDay(1)).firstResultOptional();
    }

    public static List<Budget> inMonth(YearMonth month) {
        return list("period", Sort.by("category.name"), month.atDay(1));
    }
}
