package io.tia.fixture;

import io.restassured.RestAssured;
import io.tia.junit.TeamscaleTestwiseExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith({ TeamscaleTestwiseExtension.class, RunResultWriter.class })
class ApiSmokeTest {
    @BeforeAll
    static void setup() {
        String base = System.getProperty("fixture.baseUrl");
        assumeTrue(base != null, "fixture.baseUrl 미설정 — E2E 스크립트에서만 실행");
        RestAssured.baseURI = base;
    }

    @Test void testGreeting() {                 // GreetingService + TextUtil 커버
        given().when().get("/greeting/Alice").then().statusCode(200).body(containsString("alice"));
    }

    @Test void testPrice() {                     // PricingService + TextUtil 커버
        given().when().get("/price/ABC").then().statusCode(200);
    }

    @Test void testFlaky() {                      // 의도적 플레이키
        given().when().get("/flaky").then().statusCode(200);
    }
}
