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
