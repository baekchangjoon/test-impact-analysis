package io.tia.e2e.parallel;

import io.pjacoco.testkit.junit5.PjacocoExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@Tag("parallel-tester")
@ExtendWith(PjacocoExtension.class)
class PriceTesterIT extends TesterBase {

    @Test void priceAbc()  { given().get("/price/ABC").then().statusCode(200).body(equalTo("300")); }
    @Test void priceDe()   { given().get("/price/DE").then().statusCode(200).body(equalTo("200")); }
    @Test void priceF()    { given().get("/price/F").then().statusCode(200).body(equalTo("100")); }
    @Test void priceWxyz() { given().get("/price/WXYZ").then().statusCode(200).body(equalTo("400")); }
}
