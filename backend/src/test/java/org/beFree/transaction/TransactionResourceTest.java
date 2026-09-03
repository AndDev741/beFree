package org.beFree.transaction;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class TransactionResourceTest {

    @Test
    void createAppliesDefaultsAndLists() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 12.50, "description": "almoço", "occurredOn": "2026-09-01"}
                        """)
                .when().post("/transactions")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("type", is("EXPENSE"))
                .body("currency", is("EUR"))
                .body("source", is("MANUAL"))
                .body("createdAt", notNullValue());

        given()
                .when().get("/transactions?month=2026-09")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void createWithCategory() {
        Number categoryId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name": "groceries"}
                        """)
                .when().post("/categories")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 50, "categoryId": %d}
                        """.formatted(categoryId.longValue()))
                .when().post("/transactions")
                .then()
                .statusCode(201)
                .body("category", is("groceries"));
    }

    @Test
    void rejectsMissingAmount() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description": "sem valor"}
                        """)
                .when().post("/transactions")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsMalformedMonth() {
        given()
                .when().get("/transactions?month=setembro")
                .then()
                .statusCode(400);
    }
}
