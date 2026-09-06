package org.beFree.transaction;

import io.quarkus.panache.common.Sort;
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

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@Path("/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {

    @Inject
    TransactionService transactions;

    @POST
    public RestResponse<TransactionResponse> create(TransactionRequest req) {
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        try {
            Transaction t = transactions.record(new NewTransaction(
                    req.amount(), req.type(), req.currency(), req.occurredOn(),
                    req.description(), req.categoryId(),
                    Source.MANUAL, null, null));
            return RestResponse.status(RestResponse.Status.CREATED, TransactionResponse.from(t));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
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
