package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.beFree.loan.Loan;
import org.beFree.loan.LoanRepayment;
import org.beFree.loan.LoanService;
import org.beFree.transaction.TransactionResponse;

import java.util.List;
import java.util.function.Supplier;

@Path("/api/loans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoanResource {

    @Inject
    LoanService loans;

    /** What is still owed, or with `?closed=true` the loans that are paid back or closed. */
    @GET
    @Transactional
    public List<Dto.LoanView> list(@QueryParam("closed") boolean closed) {
        return (closed ? loans.finished() : loans.open()).stream().map(LoanResource::view).toList();
    }

    static Dto.LoanView view(LoanService.Progress p) {
        Loan l = p.loan();
        List<Dto.RepaymentView> repayments = p.repayments().stream()
                .map(r -> new Dto.RepaymentView(r.id, r.amount, r.currency, r.occurredOn, r.note))
                .toList();
        return new Dto.LoanView(l.id, l.person, l.description, l.amount, l.currency, l.lentOn,
                l.transaction != null ? l.transaction.id : null,
                l.transaction != null ? l.transaction.description : null,
                p.repaid(), p.outstanding(), p.percent(), p.otherCurrencies(), p.status().name(),
                p.settledOn(), l.closedOn, l.closingNote, repayments);
    }

    @POST
    @Transactional
    public Dto.LoanView create(Dto.LoanRequest req) {
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        Loan loan = refusing(() -> loans.lend(req.person(), req.amount(), req.currency(), req.lentOn(),
                req.description(), req.transactionId()));
        return view(loans.progress(loan));
    }

    /** Fields left out keep their value; a blank description clears it. */
    @PATCH
    @Path("/{id}")
    @Transactional
    public Dto.LoanView update(@PathParam("id") long id, Dto.LoanRequest req) {
        Loan loan = find(id);
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        refusing(() -> {
            // Validate everything before touching the entity, so a refusal leaves it as it was
            String person = req.person() != null ? LoanService.person(req.person()) : loan.person;
            var amount = req.amount() != null ? LoanService.amount(req.amount()) : loan.amount;
            String currency = req.currency() != null ? LoanService.currency(req.currency()) : loan.currency;
            var transaction = loan.transaction;
            if (Boolean.TRUE.equals(req.clearTransaction())) {
                transaction = null;
            } else if (req.transactionId() != null) {
                transaction = loans.linkable(req.transactionId(), loan);
            }
            loan.person = person;
            loan.amount = amount;
            loan.currency = currency;
            loan.transaction = transaction;
            if (req.lentOn() != null) {
                loan.lentOn = req.lentOn();
            }
            if (req.description() != null) {
                loan.description = LoanService.text(req.description());
            }
            return loan;
        });
        return view(loans.progress(loan));
    }

    /** Takes its repayments with it. The movement that paid it out stays in the ledger. */
    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") long id) {
        loans.delete(find(id));
    }

    @POST
    @Path("/{id}/repayments")
    @Transactional
    public Dto.LoanView repay(@PathParam("id") long id, Dto.RepaymentRequest req) {
        Loan loan = find(id);
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        refusing(() -> loans.repay(loan, req.amount(), req.currency(), req.occurredOn(), req.note()));
        return view(loans.progress(loan));
    }

    @DELETE
    @Path("/{id}/repayments/{repaymentId}")
    @Transactional
    public Dto.LoanView unrepay(@PathParam("id") long id, @PathParam("repaymentId") long repaymentId) {
        Loan loan = find(id);
        LoanRepayment r = LoanRepayment.findById(repaymentId);
        if (r == null || !r.loan.id.equals(loan.id)) {
            throw new NotFoundException("repayment " + repaymentId + " not found on loan " + id);
        }
        r.delete();
        r.flush();
        return view(loans.progress(loan));
    }

    /**
     * Calls a loan done by hand: paid in another currency, forgiven, settled
     * some other way. The note says how.
     */
    @PUT
    @Path("/{id}/close")
    @Transactional
    public Dto.LoanView close(@PathParam("id") long id, Dto.CloseRequest req) {
        Loan loan = find(id);
        loans.close(loan, req == null ? null : req.note());
        return view(loans.progress(loan));
    }

    @DELETE
    @Path("/{id}/close")
    @Transactional
    public Dto.LoanView reopen(@PathParam("id") long id) {
        Loan loan = find(id);
        loans.reopen(loan);
        return view(loans.progress(loan));
    }

    /** Expenses filed as loans that no loan tracks yet. */
    @GET
    @Path("/untracked")
    @Transactional
    public List<TransactionResponse> untracked() {
        return loans.untracked().stream().map(TransactionResponse::from).toList();
    }

    private static Loan find(long id) {
        Loan loan = Loan.findById(id);
        if (loan == null) {
            throw new NotFoundException("loan " + id + " not found");
        }
        return loan;
    }

    private static <T> T refusing(Supplier<T> write) {
        try {
            return write.get();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
