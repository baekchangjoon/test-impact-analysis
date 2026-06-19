package io.tia.e2e.parallel;

import io.pjacoco.testkit.junit5.PjacocoExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@Tag("parallel-tester")
@ExtendWith(PjacocoExtension.class)
class GreetingTesterIT extends TesterBase {

    @Test void greetingAlice() { given().get("/greeting/Alice").then().statusCode(200).body(equalTo("hello alice")); }
    @Test void greetingBob()   { given().get("/greeting/Bob").then().statusCode(200).body(equalTo("hello bob")); }
    @Test void greetingCarol() { given().get("/greeting/Carol").then().statusCode(200).body(equalTo("hello carol")); }
    @Test void greetingDave()  { given().get("/greeting/Dave").then().statusCode(200).body(equalTo("hello dave")); }
}
