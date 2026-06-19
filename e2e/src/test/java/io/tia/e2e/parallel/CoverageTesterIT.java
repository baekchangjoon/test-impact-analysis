package io.tia.e2e.parallel;

import io.pjacoco.testkit.junit5.PjacocoExtension;
import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 단일 pjacoco SUT(fixture-app)를 때리는 결정적 블랙박스 케이스. 오케스트레이터가 직렬/forks/in-JVM 3모드로 구동.
 *  /flaky(비결정) 제외. testId = CoverageTesterIT#<method> (pjacoco 키). */
@Tag("parallel-tester")
@ExtendWith(PjacocoExtension.class)
class CoverageTesterIT {
    @BeforeAll
    static void wire() {
        String base = System.getProperty("fixture.baseUrl");
        assumeTrue(base != null, "fixture.baseUrl 미설정 — 오케스트레이터에서만 실행");
        RestAssured.baseURI = base;
        PjacocoRestAssured.enable();   // 모든 요청에 baggage: test.id=<현재testId>
    }

    @Test void greetingAlice() { given().get("/greeting/Alice").then().statusCode(200).body(equalTo("hello alice")); }
    @Test void greetingBob()   { given().get("/greeting/Bob").then().statusCode(200).body(equalTo("hello bob")); }
    @Test void greetingCarol() { given().get("/greeting/Carol").then().statusCode(200).body(equalTo("hello carol")); }
    @Test void greetingDave()  { given().get("/greeting/Dave").then().statusCode(200).body(equalTo("hello dave")); }
    @Test void priceAbc() { given().get("/price/ABC").then().statusCode(200).body(equalTo("300")); }
    @Test void priceDe()  { given().get("/price/DE").then().statusCode(200).body(equalTo("200")); }
    @Test void priceF()   { given().get("/price/F").then().statusCode(200).body(equalTo("100")); }
    @Test void priceWxyz(){ given().get("/price/WXYZ").then().statusCode(200).body(equalTo("400")); }
}
