package io.tia.fixture;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NoiseScheduler {
    @Scheduled(fixedRate = 1000)
    public void tick() { TextUtil.normalize("background-noise"); }   // 공유 유틸을 건드리는 백그라운드 노이즈
}
