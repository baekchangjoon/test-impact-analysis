package io.tia.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tia.core.config.TiaConfig;
import io.tia.core.config.TiaConfigException;
import io.tia.core.config.TiaConfigLoader;
import io.tia.core.store.CoverageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code tia doctor} — 환경·설정·인덱스 상태 6체크 진단기(design spec §3).
 * 예외로 죽지 않는다 — 개별 체크 실패는 그 항목의 FAIL로 수렴한다. 읽기 전용을 보장한다:
 * 인덱스 DB 파일이 존재할 때만 {@link CoverageStore}를 연다(생성자가 DB·스키마를 만드는 부작용 회피).
 */
@Command(name = "doctor", description = "환경·설정·인덱스 상태 진단 — PASS/WARN/FAIL/SKIP + 처방")
public class DoctorCommand implements Callable<Integer> {

    @Mixin ConfigMixin configMixin;

    @Option(names = "--db", description = "인덱스 DB 경로(미지정 시 tia.yml→기본값 해석)")
    Path db;

    @Option(names = "--working-dir", hidden = true,
            description = "테스트/MCP 시임: 기본 DB 해석 기준 디렉터리(미지정 시 프로세스 cwd). 체크 2/5/6은 --search-root 기준 유지")
    Path workingDir;

    @Option(names = "--format", defaultValue = "text",
            description = "출력 형식: ${COMPLETION-CANDIDATES} (기본 text)")
    DoctorFormat format;

    private static final ObjectMapper OM = new ObjectMapper();

    enum Status { PASS, WARN, FAIL, SKIP }

    private record Check(String id, String name, Status status, String detail, String hint) {}

    @Override
    public Integer call() {
        Path start = resolvedSearchRoot();
        List<Check> checks = new ArrayList<>();

        checks.add(checkJdk());

        Path gitRoot = RepoPaths.findGitRoot(start);
        checks.add(checkGitRepo(gitRoot));

        TiaConfig cfg = checkTiaYml(configMixin.config, start, checks);   // adds check3, returns resolved (or empty) config

        Path effectiveDb = (db != null) ? db
                : (cfg.db() != null) ? cfg.db()
                : DbPaths.resolveDefault(workingDir);    // [SP4-REQ-004] null → 기존 동작과 동일
        boolean dbExists = Files.exists(effectiveDb);
        checks.add(dbExists
                ? pass("index-db", "인덱스 DB 존재", "DB 파일 존재: " + effectiveDb)
                : warn("index-db", "인덱스 DB 존재", "DB 파일 없음: " + effectiveDb,
                        "tia index 로 먼저 인덱싱하세요"));

        checks.add(checkBaseline(start, effectiveDb, dbExists));

        checks.add(checkPjacocoAgent(start));

        print(checks);
        boolean anyFail = checks.stream().anyMatch(c -> c.status() == Status.FAIL);
        return anyFail ? 1 : 0;
    }

    /** ConfigMixin이 해석하는 search-root(--search-root ?: cwd)와 동일한 값 — 체크 2·5·6이 이 값을 쓴다. */
    private Path resolvedSearchRoot() {
        Path sr = configMixin.searchRoot;
        return (sr != null) ? sr.toAbsolutePath().normalize() : Path.of("").toAbsolutePath();
    }

    private static Check checkJdk() {
        int major = Runtime.version().feature();
        String detail = "java.version=" + System.getProperty("java.version");
        return (major >= 17)
                ? pass("jdk", "JDK 17+", detail)
                : fail("jdk", "JDK 17+", detail, "JDK 17 이상으로 업그레이드하세요");
    }

    private static Check checkGitRepo(Path gitRoot) {
        return (gitRoot != null)
                ? pass("git-repo", "git 레포 여부", "git 루트: " + gitRoot)
                : warn("git-repo", "git 레포 여부", "git 레포가 아님",
                        "git init 으로 레포를 초기화하면 diff 기반 기능을 쓸 수 있습니다");
    }

    /** 체크3(tia.yml 존재·유효성)을 checks에 추가하고, 이후 체크가 쓸 TiaConfig를 반환(없거나 실패 시 empty). */
    private static TiaConfig checkTiaYml(Path explicitConfig, Path start, List<Check> checks) {
        try {
            Optional<TiaConfig> loaded = TiaConfigLoader.load(explicitConfig, start);
            if (loaded.isPresent()) {
                checks.add(pass("tia-yml", "tia.yml 존재·유효성", "tia.yml 유효"));
                return loaded.get();
            }
            checks.add(warn("tia-yml", "tia.yml 존재·유효성", "tia.yml 없음 — 기본값으로 동작",
                    "tia init 으로 tia.yml을 생성하세요"));
            return TiaConfig.empty();
        } catch (TiaConfigException e) {
            checks.add(fail("tia-yml", "tia.yml 존재·유효성", "tia.yml 파싱 실패: " + e.getMessage(),
                    "tia.yml 문법 오류를 수정하세요"));
            return TiaConfig.empty();
        } catch (RuntimeException e) {   // 진단은 예외로 죽지 않는다 — 예상 밖 실패도 FAIL로 수렴
            checks.add(fail("tia-yml", "tia.yml 존재·유효성", "확인 중 오류: " + e.getMessage(), null));
            return TiaConfig.empty();
        }
    }

    /** 비-git → SKIP. DB 미존재 → WARN(연다면 부작용 발생하므로 열지 않음). DB 존재 시에만 열어 판정. */
    private static Check checkBaseline(Path start, Path effectiveDb, boolean dbExists) {
        String head = RepoPaths.gitHead(start);
        if (head == null) {
            return skip("baseline-head", "DB 베이스라인 ↔ HEAD 정렬", "git 레포가 아니거나 HEAD를 해석할 수 없음");
        }
        if (!dbExists) {
            return warn("baseline-head", "DB 베이스라인 ↔ HEAD 정렬", "인덱스 DB가 없어 확인 불가",
                    "tia index 로 먼저 인덱싱하세요");
        }
        try (CoverageStore store = CoverageStore.openRead(effectiveDb)) {   // [FU-REQ-003] 읽기 전용 오픈
            boolean empty = store.load(head).tests().isEmpty();
            return empty
                    ? warn("baseline-head", "DB 베이스라인 ↔ HEAD 정렬", "HEAD(" + head + ") 베이스라인 없음",
                            "tia index --commit " + head + " 로 재인덱싱하세요")
                    : pass("baseline-head", "DB 베이스라인 ↔ HEAD 정렬", "HEAD(" + head + ") 베이스라인 확인됨");
        } catch (RuntimeException e) {   // CoverageStore(SQLException 래핑) 등 — 개별 체크 FAIL로 수렴
            return fail("baseline-head", "DB 베이스라인 ↔ HEAD 정렬", "확인 중 오류: " + e.getMessage(), null);
        }
    }

    private static Check checkPjacocoAgent(Path start) {
        Path tiaRoot = RepoPaths.findTiaRepoRoot(start);
        if (tiaRoot == null) {
            return skip("pjacoco-agent", "pjacoco 에이전트 jar", "TIA 레포가 아님");
        }
        Path jar = tiaRoot.resolve("tools/pjacoco/pjacoco-agent.jar");
        return Files.exists(jar)
                ? pass("pjacoco-agent", "pjacoco 에이전트 jar", "존재: " + jar)
                : warn("pjacoco-agent", "pjacoco 에이전트 jar", "없음: " + jar,
                        "scripts/setup-pjacoco.sh 를 실행하세요");
    }

    private void print(List<Check> checks) {
        if (format == DoctorFormat.json) {
            printJson(checks);
            return;
        }
        for (Check c : checks) {
            String hintPart = (c.hint() != null && !c.hint().isBlank()) ? " (" + c.hint() + ")" : "";
            System.out.println("[" + c.status() + "] " + c.name() + " — " + c.detail() + hintPart);
        }
        System.out.println("요약: PASS " + count(checks, Status.PASS) + ", WARN " + count(checks, Status.WARN)
                + ", FAIL " + count(checks, Status.FAIL) + ", SKIP " + count(checks, Status.SKIP));
    }

    private void printJson(List<Check> checks) {
        ObjectNode root = OM.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("command", "doctor");
        ArrayNode arr = root.putArray("checks");
        for (Check c : checks) {
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("status", c.status().name());
            n.put("detail", c.detail());
            if (c.hint() != null) n.put("hint", c.hint()); else n.putNull("hint");
        }
        ObjectNode summary = root.putObject("summary");
        summary.put("pass", count(checks, Status.PASS));
        summary.put("warn", count(checks, Status.WARN));
        summary.put("fail", count(checks, Status.FAIL));
        summary.put("skip", count(checks, Status.SKIP));
        System.out.println(root.toPrettyString());
    }

    private static long count(List<Check> checks, Status status) {
        return checks.stream().filter(c -> c.status() == status).count();
    }

    private static Check pass(String id, String name, String detail) { return new Check(id, name, Status.PASS, detail, null); }
    private static Check warn(String id, String name, String detail, String hint) { return new Check(id, name, Status.WARN, detail, hint); }
    private static Check fail(String id, String name, String detail, String hint) { return new Check(id, name, Status.FAIL, detail, hint); }
    private static Check skip(String id, String name, String detail) { return new Check(id, name, Status.SKIP, detail, null); }
}
