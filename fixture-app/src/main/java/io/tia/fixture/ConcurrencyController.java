package io.tia.fixture;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("e2e")
public class ConcurrencyController {
    @GetMapping("/__concurrency__/max")
    public String maxConcurrency() {
        return String.valueOf(ConcurrencyFilter.maxSeen.get());
    }
}
