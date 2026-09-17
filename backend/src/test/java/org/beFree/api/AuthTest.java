package org.beFree.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The app is reachable from the internet, so "unauthenticated gets nothing"
 * is a property worth a test rather than a property worth assuming.
 */
@QuarkusTest
class AuthTest {

    @Test
    void apiIsClosedWithoutASession() {
        given().when().get("/api/summary").then().statusCode(401);
        given().when().get("/api/transactions").then().statusCode(401);
        given().when().get("/api/budgets").then().statusCode(401);
        given().when().get("/api/goals").then().statusCode(401);
        given().when().get("/api/session").then().statusCode(401);
    }

    @Test
    void healthAndWebhookStayOpen() {
        given().when().get("/q/health/ready").then().statusCode(200);
        given().queryParam("hub.mode", "subscribe")
                .queryParam("hub.verify_token", "nope")
                .queryParam("hub.challenge", "1")
                .when().get("/webhooks/whatsapp").then().statusCode(403); // reached the handler, not the auth wall
    }

    @Test
    void signingInOpensTheApiAndTheCookieCarriesIt() {
        String cookie = given()
                .contentType(ContentType.URLENC)
                .formParam("j_username", "andre")
                .formParam("j_password", "befree-dev")
                .when().post("/api/login")
                .then().statusCode(200)
                .extract().cookie("befree-session");

        given().cookie("befree-session", cookie)
                .when().get("/api/session")
                .then().statusCode(200).body("username", is("andre"));

        given().cookie("befree-session", cookie)
                .when().get("/api/summary")
                .then().statusCode(200);
    }

    @Test
    void theCookieIsOutOfReachOfScriptsAndLogoutExpiresIt() {
        var cookie = given()
                .contentType(ContentType.URLENC)
                .formParam("j_username", "andre")
                .formParam("j_password", "befree-dev")
                .when().post("/api/login")
                .then().statusCode(200)
                .extract().detailedCookie("befree-session");

        // An XSS anywhere in the SPA would otherwise hand over the session
        assertTrue(cookie.isHttpOnly(), "the session cookie must not be readable by scripts");

        var cleared = given().cookie("befree-session", cookie.getValue())
                .when().post("/api/logout")
                .then().statusCode(204)
                .extract().detailedCookie("befree-session");

        assertEquals("", cleared.getValue());
        assertEquals(0, cleared.getMaxAge());
    }

    @Test
    void wrongPasswordIsRejected() {
        given().contentType(ContentType.URLENC)
                .formParam("j_username", "andre")
                .formParam("j_password", "not-the-password")
                .when().post("/api/login")
                .then().statusCode(401);
    }
}
