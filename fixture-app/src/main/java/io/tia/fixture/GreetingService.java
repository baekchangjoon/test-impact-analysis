package io.tia.fixture;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    public String greet(String name) {
        return "hello " + TextUtil.normalize(name);
    }
}
