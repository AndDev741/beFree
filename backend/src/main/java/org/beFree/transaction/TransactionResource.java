package org.beFree.transaction;
import org.beFree.category.Category;

import io.quarkus.panache.common.Sort;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {

    @POST
    @Transactional
    public RestResponse<TransactionResponse> create(TransactionRequest req) {
        if (req == null || req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("amount is required and must be positive");
        }

        Transaction t = new Transaction();
        t.amount = req.amount();
        t.type = req.type() != null ? req.type() : TransactionType.EXPENSE;
        t.currency = req.currency() != null ? req.currency() : "EUR";
        t.occurredOn = req.occurredOn() != null ? req.occurredOn() : LocalDate.now();
        t.description = req.description();
        t.source = Source.MANUAL;

        if (req.categoryId() != null) {
            Category category = Category.findById(req.categoryId());
            if (category == null) {
                throw new BadRequestException("unknown category: " + req.categoryId());
            }
            t.category = category;
        }

        // Flush so @CreationTimestamp is populated before the response is built
        t.persistAndFlush();
        return RestResponse.status(RestResponse.Status.CREATED, TransactionResponse.from(t));
    }

    @GET
    @Transactional
    public List<TransactionResponse> list(@QueryParam("month") String month) {
        List<Transaction> transactions;
        if (month != null) {
            try {
                transactions = Transaction.inMonth(YearMonth.parse(month));
            } catch (DateTimeParseException e) {
                throw new BadRequestException("month must look like 2026-09");
            }
        } else {
            transactions = Transaction.listAll(Sort.descending("occurredOn"));
        }
        return transactions.stream().map(TransactionResponse::from).toList();
    }
}
