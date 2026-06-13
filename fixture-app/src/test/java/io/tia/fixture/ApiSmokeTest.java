package io.tia.fixture;

import io.restassured.RestAssured;
import io.tia.junit.TeamscaleTestwiseExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
        // greet("Alice") = "hello " + normalize("Alice") = "hello alice" — 본문값까지 정확 검증.
        given().when().get("/greeting/Alice").then().statusCode(200).body(equalTo("hello alice"));
    }

    @Test void testPrice() {                     // PricingService + TextUtil 커버
        // priceOf("ABC") = normalize("ABC")="abc"(len 3) * 100 = 300 — 본문값까지 정확 검증(200만 보지 않음).
        given().when().get("/price/ABC").then().statusCode(200).body(equalTo("300"));
    }

    @Test void testFlaky() {                      // 의도적 플레이키 — 200을 기대하나 ~50% 500 (플레이키 측정용)
        given().when().get("/flaky").then().statusCode(200).body(equalTo("ok"));
    }
}
