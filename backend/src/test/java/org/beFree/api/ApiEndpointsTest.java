package org.beFree.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.beFree.media.MediaIngest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
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
     * The cycle has to reach every window at once. A salary counted in one
     * month by the summary and in another by the movement list would be worse
     * than no setting at all, so this walks the same rows through all three.
     */
    @Test
    void movingTheStartDayMovesTheWholeMonthWithIt() {
        Number ciclo = given().contentType(ContentType.JSON).body("{\"name\":\"Ciclo\"}")
                .when().post("/api/categories").then().statusCode(201).extract().path("id");
        Number ordenado = given().contentType(ContentType.JSON)
                .body("{\"amount\":300,\"description\":\"ordenado do dia 25\",\"occurredOn\":\"2026-10-25\",\"type\":\"INCOME\"}")
                .when().post("/api/transactions").then().statusCode(201).extract().path("id");
        Number compras = given().contentType(ContentType.JSON)
                .body("{\"amount\":30,\"description\":\"compras do dia 26\",\"occurredOn\":\"2026-10-26\",\"categoryId\":%d}"
                        .formatted(ciclo.longValue()))
                .when().post("/api/transactions").then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON).body("{\"category\":\"Ciclo\",\"limitAmount\":100,\"month\":\"2026-10\"}")
                .when().put("/api/budgets").then().statusCode(200);

        try {
            // On the calendar, both land in October
            given().when().get("/api/summary?month=2026-10").then().body("income", is(300.00f)).body("spent", is(30.00f));
            given().when().get("/api/transactions?month=2026-10").then().body("size()", is(2));
            given().when().get("/api/budgets?month=2026-10")
                    .then().body("find { it.category == 'Ciclo' }.spent", is(30.00f));

            given().contentType(ContentType.JSON).body("{\"monthStartDay\":25}")
                    .when().put("/api/settings").then().statusCode(200)
                    .body("defaultStartDay", is(25))
                    .body("custom", is(false));

            given().when().get("/api/settings?month=2026-10").then().statusCode(200)
                    .body("startDay", is(25))
                    .body("from", is("2026-09-25"))
                    .body("to", is("2026-10-24"));

            // Now both belong to November, and every window agrees
            given().when().get("/api/summary?month=2026-10").then().body("income", is(0)).body("spent", is(0));
            given().when().get("/api/summary?month=2026-11").then().body("income", is(300.00f)).body("spent", is(30.00f));
            given().when().get("/api/transactions?month=2026-10").then().body("size()", is(0));
            given().when().get("/api/transactions?month=2026-11").then().body("size()", is(2));
            given().when().get("/api/budgets?month=2026-10")
                    .then().body("find { it.category == 'Ciclo' }.spent", is(0));

            // November alone starts on the 26th: the salary of the 25th falls back into October
            given().contentType(ContentType.JSON).body("{\"monthStartDay\":26}")
                    .when().put("/api/settings?month=2026-11").then().statusCode(200)
                    .body("startDay", is(26))
                    .body("custom", is(true))
                    .body("from", is("2026-10-26"))
                    .body("to", is("2026-11-24"));

            given().when().get("/api/transactions?month=2026-10").then().body("size()", is(1));
            given().when().get("/api/transactions?month=2026-11").then().body("size()", is(1));
            given().when().get("/api/summary?month=2026-10").then().body("income", is(300.00f)).body("spent", is(0));
            given().when().get("/api/summary?month=2026-11").then().body("income", is(0)).body("spent", is(30.00f));

            // Dropping the exception puts it back
            given().when().delete("/api/settings/months/2026-11").then().statusCode(200)
                    .body("custom", is(false))
                    .body("from", is("2026-10-25"));
            given().when().get("/api/transactions?month=2026-11").then().body("size()", is(2));

            given().contentType(ContentType.JSON).body("{\"monthStartDay\":29}")
                    .when().put("/api/settings").then().statusCode(400);
        } finally {
            given().contentType(ContentType.JSON).body("{\"monthStartDay\":1}")
                    .when().put("/api/settings").then().statusCode(200);
            given().when().delete("/api/settings/months/2026-11");
            given().when().delete("/api/transactions/%d".formatted(ordenado.longValue()));
            given().when().delete("/api/transactions/%d".formatted(compras.longValue()));
        }
    }

    /**
     * The whole point of saving up: when the party finally happens, it is paid
     * for by the five months that funded it, not by the month it lands in.
     */
    @Test
    void whatYouSavedForPaysForItselfWhenYouSpendIt() {
        given().contentType(ContentType.JSON).body("{\"name\":\"Festas\"}")
                .when().post("/api/categories").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"amount\":800,\"type\":\"INCOME\",\"description\":\"ordenado\",\"occurredOn\":\"2026-12-01\"}")
                .when().post("/api/transactions").then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"category\":\"Festas\",\"limitAmount\":100,\"month\":\"2026-12\"}")
                .when().put("/api/budgets").then().statusCode(200);

        // 500 put by over earlier months
        Number goal = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Festa de anos\",\"target\":500,\"initial\":500}")
                .when().post("/api/goals").then().statusCode(200)
                .body("saved", is(500.00f)).body("reached", is(true))
                .extract().path("id");

        Number party = given().contentType(ContentType.JSON)
                .body("{\"amount\":320,\"description\":\"festa\",\"occurredOn\":\"2026-12-14\",\"goalId\":%d}"
                        .formatted(((Number) goal).longValue()))
                .when().post("/api/transactions").then().statusCode(201)
                .body("goal", is("Festa de anos"))
                .extract().path("id");

        // It happened, and it is in the list
        given().when().get("/api/transactions?month=2026-12").then().body("size()", is(2));

        // But December's own money is untouched, and so is its budget
        given().when().get("/api/summary?month=2026-12").then().statusCode(200)
                .body("spent", is(0))
                .body("spentFromGoals", is(320.00f))
                .body("remaining", is(800.00f))
                .body("byCategory", hasSize(0));
        given().when().get("/api/budgets?month=2026-12")
                .then().body("find { it.category == 'Festas' }.spent", is(0));

        // The jar paid for it, so the jar is emptier
        given().when().get("/api/summary?month=2026-12")
                .then().body("goals.find { it.name == 'Festa de anos' }.saved", is(180.00f))
                .body("goals.find { it.name == 'Festa de anos' }.spent", is(320.00f))
                // Reached when it was filled, and spending it does not undo that
                .body("goals.find { it.name == 'Festa de anos' }.reached", is(true));

        // A jar only pays out what it holds
        given().contentType(ContentType.JSON)
                .body("{\"amount\":900,\"description\":\"a mais\",\"occurredOn\":\"2026-12-15\",\"goalId\":%d}"
                        .formatted(((Number) goal).longValue()))
                .when().post("/api/transactions").then().statusCode(400);

        // Unlinking it puts the money back in the jar and the expense back in the month
        given().contentType(ContentType.JSON).body("{\"clearGoal\":true}")
                .when().patch("/api/transactions/%d".formatted(((Number) party).longValue()))
                .then().statusCode(200).body("goal", nullValue());
        given().when().get("/api/summary?month=2026-12")
                .then().body("spent", is(320.00f))
                .body("spentFromGoals", is(0))
                .body("remaining", is(480.00f))
                .body("goals.find { it.name == 'Festa de anos' }.saved", is(500.00f));

        given().when().delete("/api/transactions/%d".formatted(((Number) party).longValue()));
        given().when().delete("/api/goals/%d".formatted(((Number) goal).longValue()));
    }

    /**
     * The case that prompted this: a goal you met, and are now spending, must
     * not go back to demanding a contribution every month just because the
     * balance is falling. Spending it is what you saved it for.
     */
    @Test
    void aReachedGoalStaysReachedWhileYouSpendIt() {
        Number id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Ferias 2027\",\"target\":600,\"targetDate\":\"2027-08-31\"}")
                .when().post("/api/goals").then().statusCode(200)
                .body("reached", is(false))
                .body("achievedOn", nullValue())
                .body("perMonth", notNullValue())
                .extract().path("id");
        String path = "/api/goals/%d".formatted(((Number) id).longValue());

        given().contentType(ContentType.JSON).body("{\"amount\":600,\"occurredOn\":\"2027-01-05\"}")
                .when().post(path + "/contributions").then().statusCode(200)
                .body("reached", is(true))
                .body("achievedOn", notNullValue())
                .body("perMonth", nullValue());

        // Now spend most of it: the balance falls, the achievement does not
        given().contentType(ContentType.JSON)
                .body("{\"amount\":450,\"description\":\"voos\",\"occurredOn\":\"2027-01-20\",\"goalId\":%d}"
                        .formatted(((Number) id).longValue()))
                .when().post("/api/transactions").then().statusCode(201);

        given().when().get("/api/goals")
                .then().statusCode(200)
                .body("find { it.name == 'Ferias 2027' }.saved", is(150.00f))
                .body("find { it.name == 'Ferias 2027' }.reached", is(true))
                .body("find { it.name == 'Ferias 2027' }.perMonth", nullValue());

        // And by hand, both ways
        given().contentType(ContentType.JSON).body("{\"achieved\":false}")
                .when().patch(path).then().statusCode(200)
                .body("reached", is(false))
                .body("achievedOn", nullValue())
                .body("perMonth", notNullValue());

        given().contentType(ContentType.JSON).body("{\"achieved\":true}")
                .when().patch(path).then().statusCode(200)
                .body("reached", is(true))
                .body("perMonth", nullValue());

        given().when().delete(path).then().statusCode(204);
    }

    @Test
    void anOpeningBalanceThatCoversTheTargetIsAlreadyReached() {
        Number id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Cofre cheio\",\"target\":300,\"initial\":300,\"targetDate\":\"2027-06-30\"}")
                .when().post("/api/goals").then().statusCode(200)
                .body("reached", is(true))
                .body("achievedOn", notNullValue())
                .body("perMonth", nullValue())
                .extract().path("id");
        given().when().delete("/api/goals/%d".formatted(((Number) id).longValue())).then().statusCode(204);
    }

    /**
     * Correcting a row you got wrong, which is most of what editing is for.
     * Flipping the direction has to work, and has to leave the rest coherent.
     */
    @Test
    void aMovementRecordedTheWrongWayRoundCanBeTurnedAround() {
        Number id = given().contentType(ContentType.JSON)
                .body("{\"amount\":60,\"type\":\"INCOME\",\"description\":\"enganei-me\",\"occurredOn\":\"2027-04-08\"}")
                .when().post("/api/transactions").then().statusCode(201)
                .body("type", is("INCOME")).extract().path("id");
        String path = "/api/transactions/%d".formatted(((Number) id).longValue());

        given().when().get("/api/summary?month=2027-04")
                .then().body("income", is(60.00f)).body("spent", is(0));

        given().contentType(ContentType.JSON).body("{\"type\":\"EXPENSE\"}")
                .when().patch(path).then().statusCode(200).body("type", is("EXPENSE"));

        given().when().get("/api/summary?month=2027-04")
                .then().body("income", is(0)).body("spent", is(60.00f));

        given().when().delete(path).then().statusCode(204);
    }

    /** An expense paid from a jar cannot survive being turned into income. */
    @Test
    void turningAJarPaidExpenseIntoIncomeReleasesTheJar() {
        Number goal = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Cofre edicao\",\"target\":200,\"initial\":200}")
                .when().post("/api/goals").then().statusCode(200).extract().path("id");
        long goalId = ((Number) goal).longValue();

        Number id = given().contentType(ContentType.JSON)
                .body("{\"amount\":80,\"description\":\"saiu do cofre\",\"occurredOn\":\"2027-04-10\",\"goalId\":%d}"
                        .formatted(goalId))
                .when().post("/api/transactions").then().statusCode(201)
                .body("goal", is("Cofre edicao")).extract().path("id");
        String path = "/api/transactions/%d".formatted(((Number) id).longValue());

        // Raising it beyond what the jar holds is refused, measured against the change
        given().contentType(ContentType.JSON).body("{\"amount\":500}")
                .when().patch(path).then().statusCode(400);
        // But up to the full jar is fine, even though 80 of it is this same row
        given().contentType(ContentType.JSON).body("{\"amount\":200}")
                .when().patch(path).then().statusCode(200).body("amount", is(200.00f));

        given().contentType(ContentType.JSON).body("{\"type\":\"INCOME\"}")
                .when().patch(path).then().statusCode(200)
                .body("type", is("INCOME"))
                .body("goal", nullValue());

        given().when().get("/api/goals")
                .then().body("find { it.name == 'Cofre edicao' }.saved", is(200.00f))
                .body("find { it.name == 'Cofre edicao' }.spent", is(0));

        given().when().delete(path).then().statusCode(204);
        given().when().delete("/api/goals/%d".formatted(goalId)).then().statusCode(204);
    }

    /**
     * The case that prompted archiving: saved for the party, spent the jar on
     * it, and now the goal is done. It has to leave the active goals without
     * taking its history with it, the way a delete would.
     */
    @Test
    void aFinishedGoalIsArchivedWithItsHistory() {
        Number goal = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Festa da Ines\",\"target\":300,\"targetDate\":\"2027-05-31\"}")
                .when().post("/api/goals").then().statusCode(200)
                .body("archivedOn", nullValue())
                .extract().path("id");
        long goalId = goal.longValue();
        String path = "/api/goals/%d".formatted(goalId);

        given().contentType(ContentType.JSON).body("{\"amount\":300,\"occurredOn\":\"2027-05-02\"}")
                .when().post(path + "/contributions").then().statusCode(200)
                .body("reached", is(true));
        Number party = given().contentType(ContentType.JSON)
                .body("{\"amount\":280,\"description\":\"festa\",\"occurredOn\":\"2027-05-20\",\"goalId\":%d}"
                        .formatted(goalId))
                .when().post("/api/transactions").then().statusCode(201)
                .extract().path("id");
        String partyPath = "/api/transactions/%d".formatted(party.longValue());

        String archivedOn = given().when().put(path + "/archive").then().statusCode(200)
                .body("archivedOn", notNullValue())
                .body("reached", is(true))
                .body("achievedOn", notNullValue())
                .body("saved", is(20.00f))
                .body("spent", is(280.00f))
                .body("perMonth", nullValue())
                .extract().path("archivedOn");

        // Out of the active goals and the dashboard, into the archive
        given().when().get("/api/goals").then().statusCode(200)
                .body("findAll { it.name == 'Festa da Ines' }", hasSize(0));
        given().when().get("/api/goals?archived=true").then().statusCode(200)
                .body("find { it.name == 'Festa da Ines' }.archivedOn", is(archivedOn))
                .body("find { it.name == 'Festa da Ines' }.achievedOn", notNullValue())
                .body("find { it.name == 'Festa da Ines' }.target", is(300.00f))
                .body("find { it.name == 'Festa da Ines' }.spent", is(280.00f))
                .body("findAll { it.archivedOn == null }", hasSize(0));
        // May's own figures stay put. The 300 left that month's money and the
        // party came out of the jar
        given().when().get("/api/summary?month=2027-05").then().statusCode(200)
                .body("goals.findAll { it.name == 'Festa da Ines' }", hasSize(0))
                .body("reserved", is(300.00f))
                .body("spent", is(0))
                .body("spentFromGoals", is(280.00f));

        // Archiving twice keeps the first day
        given().when().put(path + "/archive").then().statusCode(200).body("archivedOn", is(archivedOn));

        // It takes no more money, either way round
        given().contentType(ContentType.JSON).body("{\"amount\":10,\"occurredOn\":\"2027-05-25\"}")
                .when().post(path + "/contributions").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"amount\":-10,\"occurredOn\":\"2027-05-25\"}")
                .when().post(path + "/contributions").then().statusCode(400);

        // And pays for nothing new
        given().contentType(ContentType.JSON)
                .body("{\"amount\":5,\"description\":\"velas\",\"occurredOn\":\"2027-05-21\",\"goalId\":%d}"
                        .formatted(goalId))
                .when().post("/api/transactions").then().statusCode(400);
        Number cake = given().contentType(ContentType.JSON)
                .body("{\"amount\":15,\"description\":\"bolo\",\"occurredOn\":\"2027-05-21\"}")
                .when().post("/api/transactions").then().statusCode(201)
                .extract().path("id");
        String cakePath = "/api/transactions/%d".formatted(cake.longValue());
        given().contentType(ContentType.JSON).body("{\"goalId\":%d}".formatted(goalId))
                .when().patch(cakePath).then().statusCode(400);

        // What it already paid for still shows, and still edits, without losing the jar
        given().when().get("/api/transactions?month=2027-05").then()
                .body("find { it.description == 'festa' }.goal", is("Festa da Ines"));
        given().contentType(ContentType.JSON).body("{\"amount\":270,\"description\":\"festa e bolo\"}")
                .when().patch(partyPath).then().statusCode(200)
                .body("amount", is(270.00f))
                .body("goal", is("Festa da Ines"));
        given().contentType(ContentType.JSON).body("{\"goalId\":%d,\"amount\":280}".formatted(goalId))
                .when().patch(partyPath).then().statusCode(200)
                .body("goalId", is(goal.intValue()));

        // A name in the archive is still taken
        given().contentType(ContentType.JSON).body("{\"name\":\"Festa da Ines\",\"target\":100}")
                .when().post("/api/goals").then().statusCode(400);

        // A mistaken archive is undone, and the goal takes money again
        given().when().delete(path + "/archive").then().statusCode(200)
                .body("archivedOn", nullValue())
                .body("reached", is(true));
        given().when().get("/api/goals").then()
                .body("find { it.name == 'Festa da Ines' }.saved", is(20.00f));
        given().contentType(ContentType.JSON).body("{\"amount\":10,\"occurredOn\":\"2027-05-25\"}")
                .when().post(path + "/contributions").then().statusCode(200)
                .body("saved", is(30.00f));

        given().when().delete(cakePath).then().statusCode(204);
        given().when().delete(partyPath).then().statusCode(204);
        given().when().delete(path).then().statusCode(204);
    }

    /** Giving up on a goal is a way of being done with it too, and it stops asking for money. */
    @Test
    void aGoalYouGaveUpOnCanBeArchivedAndStillDeleted() {
        Number goal = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Mota nova\",\"target\":3000,\"targetDate\":\"2028-06-30\"}")
                .when().post("/api/goals").then().statusCode(200)
                .extract().path("id");
        String path = "/api/goals/%d".formatted(goal.longValue());
        given().contentType(ContentType.JSON).body("{\"amount\":120,\"occurredOn\":\"2027-07-04\"}")
                .when().post(path + "/contributions").then().statusCode(200)
                .body("perMonth", notNullValue());

        given().when().put(path + "/archive").then().statusCode(200)
                .body("reached", is(false))
                .body("achievedOn", nullValue())
                .body("perMonth", nullValue());
        given().when().get("/api/goals?archived=true").then()
                .body("find { it.name == 'Mota nova' }.reached", is(false))
                .body("find { it.name == 'Mota nova' }.saved", is(120.00f));

        given().when().put("/api/goals/987654321/archive").then().statusCode(404);
        given().when().delete("/api/goals/987654321/archive").then().statusCode(404);

        // Deleting works from the archive exactly as before
        given().when().delete(path).then().statusCode(204);
        given().when().get("/api/goals?archived=true").then()
                .body("findAll { it.name == 'Mota nova' }", hasSize(0));
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
