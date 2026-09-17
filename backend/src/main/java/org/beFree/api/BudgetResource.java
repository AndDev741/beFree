package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beFree.budget.BudgetService;
import org.beFree.category.Category;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

@Path("/api/budgets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BudgetResource {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    @Inject
    BudgetService budgets;

    @Inject
    Months months;

    @GET
    @Transactional
    public List<Dto.BudgetView> list(@QueryParam("month") String month) {
        return budgets.status(months.parse(month)).stream()
                .map(s -> new Dto.BudgetView(s.category(), s.limitAmount(), s.spent(),
                        s.remaining(), s.percentUsed(), s.overspent()))
                .toList();
    }

    @PUT
    @Transactional
    public List<Dto.BudgetView> set(Dto.BudgetRequest req) {
        if (req == null || req.category() == null || req.category().isBlank()) {
            throw new BadRequestException("category is required");
        }
        if (req.limitAmount() == null || req.limitAmount().signum() <= 0
                || req.limitAmount().compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("limit must be a positive, plausible amount");
        }
        Category category = Category.findByName(req.category().trim())
                .orElseThrow(() -> new BadRequestException("unknown category: " + req.category()));
        YearMonth ym = months.parse(req.month());
        budgets.set(category, ym, req.limitAmount().setScale(2, RoundingMode.HALF_UP));
        return list(req.month());
    }

    @DELETE
    @Path("/{category}")
    @Transactional
    public void remove(@PathParam("category") String category, @QueryParam("month") String month) {
        Category c = Category.findByName(category)
                .orElseThrow(() -> new NotFoundException("unknown category: " + category));
        if (!budgets.remove(c, months.parse(month))) {
            throw new NotFoundException("no budget for " + category);
        }
    }
}
