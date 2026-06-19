package io.tia.fixture;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("e2e")
public class ConcurrencyFilter implements Filter {
    static final AtomicInteger inFlight = new AtomicInteger(0);
    static final AtomicInteger maxSeen  = new AtomicInteger(0);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String path = http.getRequestURI();
        // Exclude the concurrency probe path itself
        if (path.startsWith("/__concurrency__")) {
            chain.doFilter(request, response);
            return;
        }
        int current = inFlight.incrementAndGet();
        maxSeen.updateAndGet(m -> Math.max(m, current));
        try {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
