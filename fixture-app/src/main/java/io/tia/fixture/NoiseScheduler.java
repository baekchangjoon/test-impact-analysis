package io.tia.fixture;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")   // E2E(-Dspring.profiles.active=e2e)에서는 백그라운드 노이즈 비활성 — 직렬/병렬 잡음 차이 배제
public class NoiseScheduler {
    @Scheduled(fixedRate = 1000)
    public void tick() { TextUtil.normalize("background-noise"); }
}
