package org.beFree.transaction;

import io.quarkus.panache.common.Sort;
import org.beFree.api.Months;
import org.beFree.category.Category;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/api/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {

    @Inject
    TransactionService transactions;

    @Inject
    Months months;

    @POST
    public RestResponse<TransactionResponse> create(TransactionRequest req) {
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        try {
            Transaction t = transactions.record(new NewTransaction(
                    req.amount(), req.type(), req.currency(), req.occurredOn(),
                    req.description(), req.categoryId(), req.goalId(),
                    Source.MANUAL, null, null));
            return RestResponse.status(RestResponse.Status.CREATED, TransactionResponse.from(t));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    public TransactionResponse update(@PathParam("id") long id, TransactionRequest req) {
        Transaction t = Transaction.findById(id);
        if (t == null) {
            throw new NotFoundException("transaction " + id + " not found");
        }
        if (req.amount() != null) {
            if (req.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("amount must be positive");
            }
            t.amount = req.amount();
        }
        if (req.type() != null) {
            t.type = req.type();
        }
        if (req.description() != null) {
            t.description = req.description().isBlank() ? null : req.description().trim();
        }
        if (req.occurredOn() != null) {
            t.occurredOn = req.occurredOn();
        }
        if (Boolean.TRUE.equals(req.clearGoal())) {
            t.goal = null;
        } else if (req.goalId() != null) {
            try {
                t.goal = transactions.payingFrom(req.goalId(), t.type, t.amount);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(req.clearCategory())) {
            t.category = null;
        } else if (req.categoryId() != null) {
            Category category = Category.findById(req.categoryId());
            if (category == null) {
                throw new BadRequestException("unknown category: " + req.categoryId());
            }
            t.category = category;
        }
        return TransactionResponse.from(t);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") long id) {
        if (!Transaction.deleteById(id)) {
            throw new NotFoundException("transaction " + id + " not found");
        }
    }

    @GET
    @Transactional
    public List<TransactionResponse> list(@QueryParam("month") String month) {
        List<Transaction> transactions;
        if (month != null) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("month must look like 2026-09");
            }
            var range = months.range(ym);
            transactions = Transaction.between(range.from(), range.to());
        } else {
            transactions = Transaction.listAll(Sort.descending("occurredOn"));
        }
        return transactions.stream().map(TransactionResponse::from).toList();
    }
}
