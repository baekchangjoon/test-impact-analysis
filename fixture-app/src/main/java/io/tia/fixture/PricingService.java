package io.tia.fixture;

import org.springframework.stereotype.Service;

@Service
public class PricingService {
    public int priceOf(String sku) {
        String key = TextUtil.normalize(sku);
        return key.length() * 100;   // 결정론적 더미 가격 (line 8)
    }
}
