package org.beFree.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Black-box run against the packaged app (the native binary under -Dnative).
 * The @QuarkusTest classes fake the identity with @TestSecurity, which does
 * nothing in packaged mode, so this is the only place that proves the real
 * chain works: bcrypt verification, the seeded account, the encrypted session
 * cookie, and Jackson reflection on the DTOs the SPA reads.
 */
@QuarkusIntegrationTest
@TestProfile(ApiIT.WithAPassword.class)
class ApiIT {

    private static final String PASSWORD = "it-password";

    public static class WithAPassword implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("befree.auth.initial-password", PASSWORD);
        }
    }

    private static String signIn(String password) {
        return given()
                .contentType(ContentType.URLENC)
                .formParam("j_username", "andre")
                .formParam("j_password", password)
                .when().post("/api/login")
                .then().statusCode(200)
                .extract().cookie("befree-session");
    }

    @Test
    void withoutASessionTheApiGivesNothingAway() {
        given().when().get("/api/summary").then().statusCode(401);
        given().when().get("/api/transactions").then().statusCode(401);
        given().when().get("/api/goals").then().statusCode(401);
    }

    @Test
    void wrongPasswordIsRejectedByTheRealHashing() {
        given().contentType(ContentType.URLENC)
                .formParam("j_username", "andre")
                .formParam("j_password", "not-the-password")
                .when().post("/api/login")
                .then().statusCode(401);
    }

    /**
     * End to end against the packaged binary: save up, reach it, spend it, and
     * check the goal does not go back to asking for a monthly contribution.
     */
    @Test
    void aGoalYouReachedAndAreSpendingStaysReached() {
        String cookie = signIn(PASSWORD);

        Number id = given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Mota\",\"target\":400,\"targetDate\":\"2027-09-30\"}")
                .when().post("/api/goals").then().statusCode(200)
                .body("reached", is(false))
                .body("perMonth", notNullValue())
                .extract().path("id");

        given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON).body("{\"amount\":400,\"occurredOn\":\"2027-02-10\"}")
                .when().post("/api/goals/%d/contributions".formatted(id.longValue()))
                .then().statusCode(200)
                .body("reached", is(true))
                .body("achievedOn", notNullValue())
                .body("perMonth", nullValue());

        given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON)
                .body("{\"amount\":250,\"description\":\"capacete e seguro\",\"occurredOn\":\"2027-02-20\",\"goalId\":%d}"
                        .formatted(id.longValue()))
                .when().post("/api/transactions").then().statusCode(201)
                .body("goal", is("Mota"));

        given().cookie("befree-session", cookie)
                .when().get("/api/goals").then().statusCode(200)
                .body("find { it.name == 'Mota' }.saved", is(150.00f))
                .body("find { it.name == 'Mota' }.spent", is(250.00f))
                .body("find { it.name == 'Mota' }.reached", is(true))
                .body("find { it.name == 'Mota' }.perMonth", nullValue());

        // February's own money never paid for any of it
        given().cookie("befree-session", cookie)
                .when().get("/api/summary?month=2027-02").then().statusCode(200)
                .body("spent", is(0))
                .body("spentFromGoals", is(250.00f));

        // Deleting the goal releases the expense instead of failing on the key
        given().cookie("befree-session", cookie)
                .when().delete("/api/goals/%d".formatted(id.longValue())).then().statusCode(204);
        given().cookie("befree-session", cookie)
                .when().get("/api/summary?month=2027-02").then()
                .body("spent", is(250.00f))
                .body("spentFromGoals", is(0));
    }

    @Test
    void signedInTheWholeSurfaceAnswers() {
        String cookie = signIn(PASSWORD);

        given().cookie("befree-session", cookie)
                .when().get("/api/session")
                .then().statusCode(200).body("username", is("andre"));

        Number category = given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON).body("{\"name\":\"Mercearia\"}")
                .when().post("/api/categories")
                .then().statusCode(201).extract().path("id");

        given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON)
                .body("{\"amount\":31.20,\"description\":\"compras\",\"occurredOn\":\"2026-11-04\",\"categoryId\":%d}"
                        .formatted(category.longValue()))
                .when().post("/api/transactions")
                .then().statusCode(201)
                .body("category", is("Mercearia"))
                .body("createdAt", notNullValue());

        given().cookie("befree-session", cookie)
                .contentType(ContentType.JSON).body("{\"category\":\"Mercearia\",\"limitAmount\":100,\"month\":\"2026-11\"}")
                .when().put("/api/budgets")
                .then().statusCode(200);

        // The dashboard in one call: the shape the SPA reads on every load
        given().cookie("befree-session", cookie)
                .when().get("/api/summary?month=2026-11")
                .then().statusCode(200)
                .body("month", is("2026-11"))
                .body("spent", is(31.20f))
                .body("byCategory[0].category", is("Mercearia"))
                .body("budgets[0].percentUsed", is(31));
    }
}
