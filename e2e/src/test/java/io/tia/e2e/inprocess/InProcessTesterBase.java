package io.tia.e2e.inprocess;

import io.pjacoco.testkit.junit5.PjacocoInProcessExtension;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/** in-JVM per-test 테스터 베이스: PjacocoInProcessExtension로 각 테스트를 감싸고,
 *  본문을 OverlapProbe로 래핑한다. testId는 pjacoco와 동일한 FQN#method. */
@ExtendWith(PjacocoInProcessExtension.class)
abstract class InProcessTesterBase {
    /** 본문을 동시성 프로브로 감싸 실행. sleep 150ms로 동시 실행 창 확보(커버 라인 불변 — 타이밍만). */
    protected void probe(TestInfo info, OverlapProbe.Body body) throws Throwable {
        String testId = info.getTestClass().orElseThrow().getName()
                + "#" + info.getTestMethod().orElseThrow().getName();
        OverlapProbe.around(testId, 150, body);
    }
}
