package io.tia.fixture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;

@RestController
public class ApiController {
    private final GreetingService greeting;
    private final PricingService pricing;

    public ApiController(GreetingService greeting, PricingService pricing) {
        this.greeting = greeting;
        this.pricing = pricing;
    }

    @GetMapping("/greeting/{name}")
    public String greeting(@PathVariable String name) { return greeting.greet(name); }

    @GetMapping("/price/{sku}")
    public int price(@PathVariable String sku) { return pricing.priceOf(sku); }

    @GetMapping("/flaky")
    public ResponseEntity<String> flaky() {                 // 의도적 비결정 — 플레이키 측정용
        boolean ok = ThreadLocalRandom.current().nextBoolean();
        return ok ? ResponseEntity.ok("ok") : ResponseEntity.status(500).body("boom");
    }
}
