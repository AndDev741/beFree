package org.beFree.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "andre", roles = "owner")
class LoanResourceTest {

    private static long lend(String body) {
        Number id = given().contentType(ContentType.JSON).body(body)
                .when().post("/api/loans")
                .then().statusCode(200)
                .extract().path("id");
        return id.longValue();
    }

    private static void repay(long loan, String body, int status) {
        given().contentType(ContentType.JSON).body(body)
                .when().post("/api/loans/%d/repayments".formatted(loan))
                .then().statusCode(status);
    }

    @Test
    void repaymentsCountDownUntilTheLoanIsSettled() {
        long id = lend("""
                {"person": "Rafaela T", "amount": 30, "lentOn": "2026-01-28", "description": "jantar"}
                """);

        given().when().get("/api/loans").then().statusCode(200)
                .body("find { it.id == %d }.status".formatted(id), is("OPEN"))
                .body("find { it.id == %d }.currency".formatted(id), is("EUR"))
                .body("find { it.id == %d }.outstanding".formatted(id), is(30.00f));

        given().contentType(ContentType.JSON).body("{\"amount\": 10, \"occurredOn\": \"2026-02-01\"}")
                .when().post("/api/loans/%d/repayments".formatted(id))
                .then().statusCode(200)
                .body("repaid", is(10.00f))
                .body("outstanding", is(20.00f))
                .body("percent", is(33))
                .body("repayments", hasSize(1));

        // Reais against a euro loan are kept as they came, never converted
        given().contentType(ContentType.JSON).body("{\"amount\": 50, \"currency\": \"brl\"}")
                .when().post("/api/loans/%d/repayments".formatted(id))
                .then().statusCode(200)
                .body("outstanding", is(20.00f))
                .body("otherCurrencies.BRL", is(50.00f));

        // More than is owed, in the loan's own currency, is a typo
        repay(id, "{\"amount\": 25}", 400);

        Number last = given().contentType(ContentType.JSON).body("{\"amount\": 20, \"occurredOn\": \"2026-03-05\"}")
                .when().post("/api/loans/%d/repayments".formatted(id))
                .then().statusCode(200)
                .body("status", is("SETTLED"))
                .body("settledOn", is("2026-03-05"))
                .body("outstanding", is(0.00f))
                .extract().path("repayments.find { it.occurredOn == '2026-03-05' }.id");

        given().when().get("/api/loans").then()
                .body("id", not(hasItem((int) id)));
        given().when().get("/api/loans?closed=true").then()
                .body("find { it.id == %d }.status".formatted(id), is("SETTLED"));

        // Taking back the repayment that settled it reopens it, nothing stored to undo
        given().when().delete("/api/loans/%d/repayments/%d".formatted(id, last.longValue()))
                .then().statusCode(200)
                .body("status", is("OPEN"))
                .body("settledOn", nullValue())
                .body("outstanding", is(20.00f));
    }

    @Test
    void aLoanRepaidInAnotherCurrencyIsClosedByHand() {
        long id = lend("""
                {"person": "Pai T", "amount": 652.54, "currency": "EUR", "lentOn": "2026-02-15"}
                """);
        repay(id, "{\"amount\": 1100, \"currency\": \"BRL\", \"note\": \"cofrinho\"}", 200);

        given().contentType(ContentType.JSON).body("{\"note\": \"R$ 3.900 cobriram\"}")
                .when().put("/api/loans/%d/close".formatted(id))
                .then().statusCode(200)
                .body("status", is("CLOSED"))
                .body("closedOn", notNullValue())
                .body("closingNote", is("R$ 3.900 cobriram"))
                .body("outstanding", is(652.54f));

        // Closed is closed: money for it waits until it is reopened
        repay(id, "{\"amount\": 10}", 400);

        given().when().delete("/api/loans/%d/close".formatted(id))
                .then().statusCode(200)
                .body("status", is("OPEN"))
                .body("closedOn", nullValue())
                .body("closingNote", nullValue());
    }

    @Test
    void aLoanPointsAtTheExpenseThatPaidItOut() {
        Number category = given().contentType(ContentType.JSON).body("{\"name\": \"Empréstimos T\"}")
                .when().post("/api/categories").then().statusCode(201).extract().path("id");
        Number expense = given().contentType(ContentType.JSON)
                .body("{\"amount\": 811.69, \"description\": \"pro meu pai\", \"occurredOn\": \"2026-10-14\", \"categoryId\": %d}"
                        .formatted(category.longValue()))
                .when().post("/api/transactions").then().statusCode(201).extract().path("id");
        Number income = given().contentType(ContentType.JSON)
                .body("{\"amount\": 5, \"type\": \"INCOME\", \"occurredOn\": \"2026-10-14\"}")
                .when().post("/api/transactions").then().statusCode(201).extract().path("id");

        given().when().get("/api/loans/untracked").then().statusCode(200)
                .body("id", hasItem(expense.intValue()));

        long id = lend("""
                {"person": "Pai T2", "amount": 811.69, "lentOn": "2026-10-14", "transactionId": %d}
                """.formatted(expense.longValue()));
        given().when().get("/api/loans").then()
                .body("find { it.id == %d }.transactionId".formatted(id), is(expense.intValue()))
                .body("find { it.id == %d }.transactionDescription".formatted(id), is("pro meu pai"));
        given().when().get("/api/loans/untracked").then()
                .body("id", not(hasItem(expense.intValue())));

        // One expense, one loan; and income never paid a loan out
        given().contentType(ContentType.JSON)
                .body("{\"person\": \"Outro\", \"amount\": 10, \"transactionId\": %d}".formatted(expense.longValue()))
                .when().post("/api/loans").then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"person\": \"Outro\", \"amount\": 10, \"transactionId\": %d}".formatted(income.longValue()))
                .when().post("/api/loans").then().statusCode(400);

        // Deleting the movement only drops the link
        given().when().delete("/api/transactions/%d".formatted(expense.longValue())).then().statusCode(204);
        given().when().get("/api/loans").then()
                .body("find { it.id == %d }.transactionId".formatted(id), nullValue())
                .body("find { it.id == %d }.outstanding".formatted(id), is(811.69f));
    }

    @Test
    void editsKeepWhatIsLeftOut() {
        long id = lend("""
                {"person": "Paulinha T", "amount": 200, "currency": "BRL", "lentOn": "2026-08-01"}
                """);
        given().contentType(ContentType.JSON).body("{\"amount\": 205, \"description\": \"R$ 200 + R$ 5 em 23/08\"}")
                .when().patch("/api/loans/%d".formatted(id))
                .then().statusCode(200)
                .body("person", is("Paulinha T"))
                .body("currency", is("BRL"))
                .body("amount", is(205.00f))
                .body("lentOn", is("2026-08-01"))
                .body("description", is("R$ 200 + R$ 5 em 23/08"));

        given().when().delete("/api/loans/%d".formatted(id)).then().statusCode(204);
        given().when().delete("/api/loans/%d".formatted(id)).then().statusCode(404);
    }

    @Test
    void refusesWhatCannotBeALoan() {
        given().contentType(ContentType.JSON).body("{\"amount\": 10}")
                .when().post("/api/loans").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"person\": \"X\", \"amount\": -5}")
                .when().post("/api/loans").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"person\": \"X\", \"amount\": 5, \"currency\": \"EURO\"}")
                .when().post("/api/loans").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"amount\": 5}")
                .when().post("/api/loans/999999/repayments").then().statusCode(404);
    }
}
