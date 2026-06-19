package io.tia.e2e.inprocess;

import io.tia.fixture.PricingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("inprocess-tester")
class PriceInProcessIT extends InProcessTesterBase {
    private final PricingService svc = new PricingService();
    @Test void priceAbc(TestInfo i)  throws Throwable { probe(i, () -> assertEquals(300, svc.priceOf("ABC"))); }
    @Test void priceDe(TestInfo i)   throws Throwable { probe(i, () -> assertEquals(200, svc.priceOf("DE"))); }
    @Test void priceF(TestInfo i)    throws Throwable { probe(i, () -> assertEquals(100, svc.priceOf("F"))); }
    @Test void priceWxyz(TestInfo i) throws Throwable { probe(i, () -> assertEquals(400, svc.priceOf("WXYZ"))); }
}
