package io.tia.e2e.inprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 공유 파일에 testId의 START/END(epochMillis)를 원자적으로 기록 — in-process 동시성 실증용.
 *  경로는 시스템 프로퍼티 tia.inprocess.overlapFile; 미설정 시 no-op. POSIX(O_APPEND) 원자 + in-JVM은 synchronized. */
final class OverlapProbe {
    private static final Object LOCK = new Object();
    private OverlapProbe() {}

    interface Body { void run() throws Throwable; }

    static void record(String testId, String phase) {
        String path = System.getProperty("tia.inprocess.overlapFile");
        if (path == null || path.isBlank()) return;
        String line = testId + "," + phase + "," + System.currentTimeMillis() + "\n";
        try {
            synchronized (LOCK) {
                Files.write(Path.of(path), line.getBytes(UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) { /* best-effort */ }
    }

    /** START 기록 → 지연(동시성 창 확보) → 본문 → END 기록(finally). */
    static void around(String testId, long sleepMs, Body body) throws Throwable {
        record(testId, "START");
        try {
            Thread.sleep(sleepMs);
            body.run();
        } finally {
            record(testId, "END");
        }
    }
}
