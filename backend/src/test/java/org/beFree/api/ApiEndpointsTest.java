package org.beFree.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.beFree.media.MediaIngest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestSecurity(user = "andre", roles = "owner")
class ApiEndpointsTest {

    @Test
    void budgetReportsSpendingAgainstItsLimit() {
        given().contentType(ContentType.JSON).body("{\"name\":\"Viagens\"}")
                .when().post("/api/categories").then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"category\":\"Viagens\",\"limitAmount\":100,\"month\":\"2026-04\"}")
                .when().put("/api/budgets").then().statusCode(200);

        Number id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Viagens\"}").when().get("/api/categories")
                .then().statusCode(200).extract().path("find { it.name == 'Viagens' }.id");

        given().contentType(ContentType.JSON)
                .body("{\"amount\":30,\"description\":\"comboio\",\"occurredOn\":\"2026-04-08\",\"categoryId\":%d}"
                        .formatted(((Number) id).longValue()))
                .when().post("/api/transactions").then().statusCode(201);

        given().queryParam("month", "2026-04").when().get("/api/budgets")
                .then().statusCode(200)
                .body("find { it.category == 'Viagens' }.spent", is(30.00f))
                .body("find { it.category == 'Viagens' }.remaining", is(70.00f))
                .body("find { it.category == 'Viagens' }.overspent", is(false));

        given().queryParam("month", "2026-04").when().delete("/api/budgets/Viagens").then().statusCode(204);
        given().queryParam("month", "2026-04").when().get("/api/budgets")
                .then().statusCode(200).body("findAll { it.category == 'Viagens' }", hasSize(0));
    }

    @Test
    void goalAccumulatesContributionsAndPaces() {
        Number goalId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Bicicleta\",\"target\":600,\"targetDate\":\"2027-01-01\"}")
                .when().post("/api/goals").then().statusCode(200)
                .extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"amount\":150,\"occurredOn\":\"2026-05-02\",\"note\":\"maio\"}")
                .when().post("/api/goals/%d/contributions".formatted(((Number) goalId).longValue()))
                .then().statusCode(200)
                .body("saved", is(150.00f))
                .body("remaining", is(450.00f))
                .body("percent", is(25))
                .body("perMonth", notNullValue());

        given().when().get("/api/goals").then().statusCode(200)
                .body("find { it.name == 'Bicicleta' }.saved", is(150.00f));

        given().when().delete("/api/goals/%d".formatted(((Number) goalId).longValue())).then().statusCode(204);
    }

    @Test
    void summaryKeepsSavingsOutOfSpending() {
        given().contentType(ContentType.JSON)
                .body("{\"amount\":2000,\"type\":\"INCOME\",\"description\":\"ordenado\",\"occurredOn\":\"2026-02-01\"}")
                .when().post("/api/transactions").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"amount\":40,\"description\":\"jantar\",\"occurredOn\":\"2026-02-03\"}")
                .when().post("/api/transactions").then().statusCode(201);

        Number goalId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Reserva junho\",\"target\":1000}")
                .when().post("/api/goals").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"amount\":300,\"occurredOn\":\"2026-02-05\"}")
                .when().post("/api/goals/%d/contributions".formatted(((Number) goalId).longValue()))
                .then().statusCode(200);

        given().queryParam("month", "2026-02").when().get("/api/summary")
                .then().statusCode(200)
                .body("income", is(2000.00f))
                .body("spent", is(40.00f))
                .body("reserved", is(300.00f))
                .body("remaining", is(1660.00f))
                .body("byCategory.size()", is(1));
    }

    @Test
    void transactionsCanBeEditedAndDeleted() {
        Number id = given().contentType(ContentType.JSON)
                .body("{\"amount\":9,\"description\":\"erro\",\"occurredOn\":\"2026-01-09\"}")
                .when().post("/api/transactions").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"amount\":12.5,\"description\":\"corrigido\"}")
                .when().patch("/api/transactions/%d".formatted(((Number) id).longValue()))
                .then().statusCode(200)
                .body("amount", is(12.50f))
                .body("description", is("corrigido"));

        given().when().delete("/api/transactions/%d".formatted(((Number) id).longValue())).then().statusCode(204);
        given().when().delete("/api/transactions/%d".formatted(((Number) id).longValue())).then().statusCode(404);
    }

    @Test
    void aMovementCanChangeCategoryAndLoseItAgain() {
        Number bills = given().contentType(ContentType.JSON).body("{\"name\":\"Contas\"}")
                .when().post("/api/categories").then().statusCode(201).extract().path("id");

        Number id = given().contentType(ContentType.JSON)
                .body("{\"amount\":40,\"description\":\"luz\",\"occurredOn\":\"2026-03-04\"}")
                .when().post("/api/transactions").then().statusCode(201)
                .body("category", nullValue())
                .extract().path("id");

        String path = "/api/transactions/%d".formatted(id.longValue());

        given().contentType(ContentType.JSON).body("{\"categoryId\":%d}".formatted(bills.longValue()))
                .when().patch(path).then().statusCode(200).body("category", is("Contas"));

        // Editing something else must not quietly drop the category
        given().contentType(ContentType.JSON).body("{\"description\":\"luz de março\"}")
                .when().patch(path).then().statusCode(200).body("category", is("Contas"));

        given().contentType(ContentType.JSON).body("{\"clearCategory\":true}")
                .when().patch(path).then().statusCode(200).body("category", nullValue());

        given().contentType(ContentType.JSON).body("{\"categoryId\":999999}")
                .when().patch(path).then().statusCode(400);
    }

    @Test
    void aGoalCanBeRenamedRetargetedAndLoseItsDate() {
        Number id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Mota\",\"target\":2000,\"targetDate\":\"2027-01-31\"}")
                .when().post("/api/goals").then().statusCode(200).extract().path("id");

        String path = "/api/goals/%d".formatted(id.longValue());

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Mota usada\",\"target\":1500,\"description\":\"com capacete\"}")
                .when().patch(path).then().statusCode(200)
                .body("name", is("Mota usada"))
                .body("target", is(1500.00f))
                .body("description", is("com capacete"))
                .body("targetDate", is("2027-01-31"));

        given().contentType(ContentType.JSON).body("{\"clearTargetDate\":true}")
                .when().patch(path).then().statusCode(200)
                .body("targetDate", nullValue())
                .body("perMonth", nullValue());

        given().contentType(ContentType.JSON).body("{\"target\":-5}")
                .when().patch(path).then().statusCode(400);

        given().when().delete(path).then().statusCode(204);
    }

    @Test
    void moneyAlreadyInTheJarCountsForTheGoalButNotAgainstTheMonth() {
        given().contentType(ContentType.JSON)
                .body("{\"amount\":900,\"type\":\"INCOME\",\"description\":\"ordenado\",\"occurredOn\":\"2026-05-01\"}")
                .when().post("/api/transactions").then().statusCode(201);

        Number id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Cofre\",\"target\":1000,\"initial\":400}")
                .when().post("/api/goals").then().statusCode(200)
                .body("initial", is(400.00f))
                .body("saved", is(400.00f))
                .body("percent", is(40))
                .extract().path("id");

        // The whole point: 400 that was already there is not 400 set aside in May
        given().when().get("/api/summary?month=2026-05")
                .then().statusCode(200)
                .body("reserved", is(0))
                .body("remaining", is(900.00f));

        given().contentType(ContentType.JSON).body("{\"amount\":100,\"occurredOn\":\"2026-05-20\"}")
                .when().post("/api/goals/%d/contributions".formatted(id.longValue()))
                .then().statusCode(200)
                .body("saved", is(500.00f));

        // A real contribution still does come out of the month
        given().when().get("/api/summary?month=2026-05")
                .then().statusCode(200)
                .body("reserved", is(100.00f))
                .body("remaining", is(800.00f));

        given().contentType(ContentType.JSON).body("{\"initial\":5000}")
                .when().patch("/api/goals/%d".formatted(id.longValue()))
                .then().statusCode(400);

        given().when().delete("/api/goals/%d".formatted(id.longValue())).then().statusCode(204);
    }

    /**
     * The carried balance reaches back over every month there has ever been, so
     * other tests in this class sit behind it. Assert the property, not a
     * hardcoded figure: what a month ends with is what the next one begins with.
     */
    @Test
    void whatIsLeftOverShowsUpAtTheStartOfTheNextMonth() {
        float aprilBefore = figure("2026-04", "remaining");

        given().contentType(ContentType.JSON)
                .body("{\"amount\":1000,\"type\":\"INCOME\",\"description\":\"ordenado abril\",\"occurredOn\":\"2026-04-02\"}")
                .when().post("/api/transactions").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"amount\":250,\"description\":\"compras abril\",\"occurredOn\":\"2026-04-11\"}")
                .when().post("/api/transactions").then().statusCode(201);

        float april = figure("2026-04", "remaining");
        assertEquals(aprilBefore + 750f, april, 0.005f);

        // May opens with exactly what April closed with; 750 did not evaporate
        assertEquals(april, figure("2026-05", "carried"), 0.005f);

        float juneBefore = figure("2026-06", "carried");
        given().contentType(ContentType.JSON)
                .body("{\"amount\":100,\"description\":\"compras maio\",\"occurredOn\":\"2026-05-06\"}")
                .when().post("/api/transactions").then().statusCode(201);
        assertEquals(juneBefore - 100f, figure("2026-06", "carried"), 0.005f);

        // Setting money aside for a goal leaves the pot too
        float julyBefore = figure("2026-07", "carried");
        Number goal = given().contentType(ContentType.JSON).body("{\"name\":\"Poupar junho\",\"target\":900}")
                .when().post("/api/goals").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON).body("{\"amount\":60,\"occurredOn\":\"2026-06-09\"}")
                .when().post("/api/goals/%d/contributions".formatted(goal.longValue())).then().statusCode(200);
        assertEquals(julyBefore - 60f, figure("2026-07", "carried"), 0.005f);

        given().when().delete("/api/goals/%d".formatted(goal.longValue())).then().statusCode(204);
    }

    private static float figure(String month, String field) {
        return ((Number) given().when().get("/api/summary?month=" + month)
                .then().statusCode(200).extract().path(field)).floatValue();
    }

    @Test
    void theChatRefusesAFileItCannotRead() {
        // Reaches MediaIngest and comes back with the wording, never touching the model
        given().multiPart("file", "notas.txt", "isto não é um recibo".getBytes(), "text/plain")
                .when().post("/api/chat/media").then().statusCode(200)
                .body("reply", is(MediaIngest.UNSUPPORTED));

        given().multiPart("file", "vazio.pdf", new byte[0], "application/pdf")
                .when().post("/api/chat/media").then().statusCode(400);
    }

    @Test
    void chatIsUnavailableWithoutAModelKey() {
        given().contentType(ContentType.JSON).body("{\"message\":\"quanto gastei?\"}")
                .when().post("/api/chat").then().statusCode(503);
        given().contentType(ContentType.JSON).body("{\"message\":\"\"}")
                .when().post("/api/chat").then().statusCode(400);
    }
}
