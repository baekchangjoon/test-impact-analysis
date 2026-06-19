package io.tia.e2e.parallel;

import io.pjacoco.testkit.restassured.PjacocoRestAssured;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 공유 설정 기반 클래스 — parallel-tester 클래스가 동일 JVM에서 동시 실행될 때 전역 상태 경합 방지. */
abstract class TesterBase {

    // 한 번만 초기화 — 두 서브클래스의 @BeforeAll이 동시에 PjacocoRestAssured.enable()을 호출하는 경합 방지
    private static volatile boolean pjacocoEnabled = false;
    private static final Object INIT_LOCK = new Object();

    @BeforeAll
    static void wireBase() {
        String base = System.getProperty("fixture.baseUrl");
        assumeTrue(base != null, "fixture.baseUrl 미설정 — 오케스트레이터에서만 실행");
        // baseURI는 모든 스레드가 동일 값을 동시에 기록하는 write-write race이지만 값이 같으므로 무해
        RestAssured.baseURI = base;
        // PjacocoRestAssured.enable()은 전역 필터를 추가하므로 한 번만 호출
        synchronized (INIT_LOCK) {
            if (!pjacocoEnabled) {
                PjacocoRestAssured.enable();
                pjacocoEnabled = true;
            }
        }
    }
}
