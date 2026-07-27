package io.tia.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code tia demo} — TIA 레포 안에서 fixture-app 실수집 → 인덱싱 → diff → impact → 리포트
 * 전 과정을 해설하며 한 번에 구동하는 체험 러너(design spec §4).
 *
 * <p>1·2단계(pjacoco 해소·실수집)는 경량 서브프로세스 스크립트로, 3~6단계(인덱싱~리포트)는
 * 별도 tia 프로세스 없이 인프로세스 picocli 호출로 구동한다. 인프로세스 단계의 실패 출력은
 * 호출 주변에서 {@code System.err}를 버퍼로 임시 스왑(+finally 복원)해 캡처한다.
 */
@Command(name = "demo",
        description = "TIA 레포 안에서 fixture-app 실수집→인덱싱→diff→impact→리포트 전 과정을 해설하며 체험")
public class DemoCommand implements Callable<Integer> {

    private static final int TAIL_LINES = 20;
    private static final String DOCTOR_HINT = "힌트: tia doctor 로 환경·설정 상태를 점검하세요.";

    @Option(names = "--repo-root", hidden = true,
            description = "TIA 레포 탐지 시작점(테스트 시임; 기본 cwd)")
    Path repoRootOpt;

    @Option(names = "--scripts-dir", hidden = true,
            description = "수집 스크립트 디렉터리(테스트 시임; 기본 <repoRoot>/scripts)")
    Path scriptsDirOpt;

    @Option(names = "--out-dir", hidden = true,
            description = "산출물 디렉터리(테스트 시임; 기본 <repoRoot>/build/inprocess-e2e/demo)")
    Path outDirOpt;

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        Path start = (repoRootOpt != null) ? repoRootOpt.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath();

        Path repoRoot = RepoPaths.findTiaRepoRoot(start);
        if (repoRoot == null) {
            System.err.println("ERROR: TIA 레포 안에서 실행해야 합니다(현재 위치에서 상향 탐색으로 발견 못함): " + start);
            System.err.println("힌트: git clone https://github.com/baekchangjoon/test-impact-analysis.git 로 레포를 받은 뒤, 그 안에서 tia demo 를 실행하세요.");
            return 1;
        }

        Path scriptsDir = (scriptsDirOpt != null) ? scriptsDirOpt : repoRoot.resolve("scripts");
        Path outDir = (outDirOpt != null) ? outDirOpt : repoRoot.resolve("build/inprocess-e2e/demo");
        Files.createDirectories(outDir);

        String head = RepoPaths.gitHead(repoRoot);
        if (head == null) {
            System.err.println("ERROR: git HEAD를 해석할 수 없습니다(레포 루트: " + repoRoot + ").");
            System.err.println(DOCTOR_HINT);
            return 1;
        }

        // [1/6] pjacoco 에이전트 해소
        printStage(1, "pjacoco 에이전트 해소");
        System.out.println("in-process 커버리지 수집에 쓰는 pjacoco 에이전트 jar를 내려받거나 재사용합니다(보통 수 초).");
        TailBuffer tail1 = new TailBuffer();
        int code1 = runScript(scriptsDir.resolve("setup-pjacoco.sh"), List.of(), repoRoot, tail1);
        if (code1 != 0) return failStage("[1/6] pjacoco 에이전트 해소", tail1);

        // [2/6] fixture-app 실수집(serial)
        printStage(2, "fixture-app 실수집(serial)");
        System.out.println("fixture-app 테스트를 pjacoco in-process 에이전트로 실제 실행해 커버리지를 모읍니다.");
        System.out.println("첫 실행은 1~3분 걸릴 수 있습니다(의존성/데몬 워밍업).");
        TailBuffer tail2 = new TailBuffer();
        int code2 = runScript(scriptsDir.resolve("demo-collect.sh"), List.of(outDir.toString()), repoRoot, tail2);
        if (code2 != 0) return failStage("[2/6] fixture-app 실수집", tail2);

        Path testwise = outDir.resolve("testwise_serial.json");

        // [3/6] 인덱싱 — demo 전용 DB(공유 DB 오염 방지)
        printStage(3, "인덱싱 — testwise 결과를 demo 전용 DB에 저장");
        Path demoDb = outDir.resolve("demo-tia.db");
        System.out.println("커밋 " + head + " 기준으로 " + testwise + " 를 " + demoDb + " 에 인덱싱합니다.");
        int code3 = execInProcess("[3/6] 인덱싱",
                "index", "--report", testwise.toString(), "--repo", "fixture", "--commit", head,
                "--db", demoDb.toString());
        if (code3 != 0) return code3;

        // [4/6] diff 동적 생성 — 커버된 라인 하나를 골라 최소 변경 생성(비파괴, HEAD 무관)
        printStage(4, "diff 동적 생성 — 커버된 라인 하나를 골라 최소 변경 만들기");
        DiffTarget target = pickFirstCoveredLine(testwise);
        if (target == null) {
            System.err.println("ERROR: testwise 리포트에 커버된 라인이 0개입니다 — 수집이 실패했을 수 있습니다.");
            System.err.println(DOCTOR_HINT);
            return 1;
        }
        Path demoDiff = outDir.resolve("demo.diff");
        writeDiff(demoDiff, target);
        System.out.println("변경 대상: " + target.file() + " 라인 " + target.line()
                + " (실제 파일은 건드리지 않는 diff 텍스트만 생성)");

        // [5/6] 영향 테스트 선별
        printStage(5, "영향 테스트 선별 — diff와 커버리지 교차");
        System.out.println("방금 만든 diff와 커버리지 매핑을 교차해, 라인 매핑이 확정된(DETERMINISTIC) 영향 테스트를 선별합니다.");
        int code5 = execInProcess("[5/6] 영향 테스트 선별",
                "impact", "--db", demoDb.toString(), "--commit", head, "--diff-file", demoDiff.toString());
        if (code5 != 0) return code5;

        // [6/6] 리포트 생성
        printStage(6, "인터랙티브 HTML 리포트 생성");
        Path reportHtml = outDir.resolve("report.html");
        int code6 = execInProcess("[6/6] 리포트 생성",
                "report", "--testwise", testwise.toString(), "--commit", head,
                "--out", reportHtml.toString(), "--sut-name", "fixture-app");
        if (code6 != 0) return code6;

        System.out.println();
        System.out.println("데모 완료 — 방금 한 일:");
        System.out.println("  1) fixture-app 테스트를 실제로 실행해 커버리지를 수집했습니다: " + testwise);
        System.out.println("  2) 커밋 " + head + " 기준으로 인덱싱했습니다: " + demoDb);
        System.out.println("  3) " + target.file() + " 라인 " + target.line() + " 변경에 영향받는 테스트를 선별했습니다.");
        System.out.println("  4) 인터랙티브 리포트를 생성했습니다(브라우저로 여세요): " + reportHtml);
        System.out.println("내 프로젝트에 적용하려면: tia init");
        return 0;
    }

    private static void printStage(int n, String title) {
        System.out.println();
        System.out.println("=== [" + n + "/6] " + title + " ===");
    }

    private static int failStage(String stageLabel, TailBuffer tail) {
        System.err.println();
        System.err.println("ERROR: " + stageLabel + " 실패. 최근 출력(최대 " + TAIL_LINES + "줄):");
        System.err.println(tail.join());
        System.err.println(DOCTOR_HINT);
        return 1;
    }

    /** 스크립트를 서브프로세스로 구동한다. stdout/stderr는 실시간으로 그대로 전달(호출측이 System.out/err
     *  스왑 시 그 스트림으로 흘러간다)하면서, stderr의 마지막 {@value #TAIL_LINES}줄을 tail에 축적한다. */
    private static int runScript(Path script, List<String> args, Path workDir, TailBuffer tail)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(script.toString());
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(false).start();
        Thread outPump = pump(p.getInputStream(), System.out, null);
        Thread errPump = pump(p.getErrorStream(), System.err, tail);
        int code = p.waitFor();
        outPump.join();
        errPump.join();
        return code;
    }

    private static Thread pump(InputStream in, PrintStream sink, TailBuffer tail) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.println(line);
                    if (tail != null) tail.add(line);
                }
            } catch (IOException ignored) {
                // 파이프가 예기치 않게 끊긴 경우 — waitFor()의 종료코드로 실패가 이미 드러남
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 인프로세스 picocli 구동. 실패 시({@code code != 0}) System.err를 임시 버퍼로 스왑해 캡처한
     *  마지막 {@value #TAIL_LINES}줄 + doctor 안내를 출력한다(+finally로 반드시 복원). */
    private static int execInProcess(String stageLabel, String... args) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = new CommandLine(new TiaCommand()).execute(args);
        } finally {
            System.setErr(originalErr);
        }
        String captured = buf.toString(StandardCharsets.UTF_8);
        if (code != 0) {
            System.err.println();
            System.err.println("ERROR: " + stageLabel + " 실패. 최근 출력(최대 " + TAIL_LINES + "줄):");
            System.err.println(lastLines(captured, TAIL_LINES));
            System.err.println(DOCTOR_HINT);
        } else if (!captured.isBlank()) {
            System.err.print(captured);   // WARN/INFO 등은 성공 시에도 그대로 보여준다
        }
        return code;
    }

    private static String lastLines(String text, int max) {
        String[] lines = text.split("\n", -1);
        int from = Math.max(0, lines.length - max);
        return String.join("\n", java.util.Arrays.asList(lines).subList(from, lines.length));
    }

    private record DiffTarget(String file, int line) {}

    /** testwise_serial.json에서 첫 번째 커버된 (파일, 라인)을 고른다. 커버된 라인이 하나도 없으면 null. */
    private static DiffTarget pickFirstCoveredLine(Path testwiseJson) throws IOException {
        JsonNode root = OM.readTree(testwiseJson.toFile());
        for (JsonNode test : root.path("tests")) {
            for (JsonNode pathNode : test.path("paths")) {
                String dir = pathNode.path("path").asText("");
                for (JsonNode file : pathNode.path("files")) {
                    String fileName = file.path("fileName").asText();
                    Integer firstLine = firstLineOf(file.path("coveredLines").asText(""));
                    if (firstLine != null) {
                        String full = dir.isEmpty() ? fileName : dir + "/" + fileName;
                        return new DiffTarget(full, firstLine);
                    }
                }
            }
        }
        return null;
    }

    /** "6-8,20" 형태의 첫 구간 시작 라인. 빈/파싱불가면 null. */
    private static Integer firstLineOf(String ranges) {
        if (ranges == null || ranges.isBlank()) return null;
        String first = ranges.split(",")[0].trim();
        if (first.isEmpty()) return null;
        int dash = first.indexOf('-');
        String lo = (dash < 0) ? first : first.substring(0, dash);
        try {
            return Integer.parseInt(lo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** old-side 라인 공간에서 target.line() 한 줄만 바꾸는 최소 unified diff(비파괴 — 실제 파일 미변경). */
    private static void writeDiff(Path diffFile, DiffTarget target) throws IOException {
        String diff = "--- a/" + target.file() + "\n"
                + "+++ b/" + target.file() + "\n"
                + "@@ -" + target.line() + ",1 +" + target.line() + ",1 @@\n"
                + "-x\n"
                + "+y\n";
        Files.writeString(diffFile, diff);
    }

    /** 실패 요약용 마지막 N줄 롤링 버퍼(서브프로세스 stderr 캡처). */
    private static final class TailBuffer {
        private final Deque<String> lines = new ArrayDeque<>();

        synchronized void add(String line) {
            lines.addLast(line);
            if (lines.size() > TAIL_LINES) lines.removeFirst();
        }

        synchronized String join() {
            return String.join("\n", lines);
        }
    }
}
