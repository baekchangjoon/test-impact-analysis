package io.tia.cli;

import io.tia.core.config.TiaConfigLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code tia init} — 프로젝트를 감지해 {@code tia.yml}을 생성하는 마법사(design spec §2).
 * 비대화형 플래그가 1급 경로이고, 대화형(TTY)은 같은 로직 위의 얇은 입력 수집이다.
 */
@Command(name = "init", description = "tia.yml 생성 마법사 — 수집 토폴로지 결정 트리 + 다음 단계 안내")
public class InitCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Option(names = "--topology",
            description = "수집 토폴로지: in-process | out-of-process (비대화형 환경에서는 필수)")
    String topology;

    @Option(names = "--sut-name", description = "SUT 이름 (기본: 대상 디렉터리 이름)")
    String sutName;

    @Option(names = "--include-code",
            description = "생성될 tia.yml의 filters.code.include 시드 글로브(반복 가능, package-relative)")
    List<String> includeCode;

    @Option(names = "--force", description = "상향 탐색으로 발견된 기존 tia.yml을 덮어쓴다")
    boolean force;

    @Option(names = "--search-root", hidden = true,
            description = "탐색·쓰기 기준점(테스트 시임; 기본 cwd)")
    Path searchRoot;

    @Override
    public Integer call() throws Exception {
        Path start = (searchRoot != null) ? searchRoot.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath();

        // 쓰기 대상 [SP3-REQ-002]: git 루트가 있으면 그곳, 없으면 탐색 시작점(cwd 상당).
        Path writeTarget = RepoPaths.findGitRoot(start);
        Path targetDir = (writeTarget != null) ? writeTarget : start;
        Path targetFile = targetDir.resolve("tia.yml");

        // 1) 사전 감지(design spec §2 step1): git 여부(경고만) + 빌드 도구(다음 단계 안내 분기용).
        if (writeTarget == null) {
            System.out.println(gitMissingWarningText());
        }
        BuildTool buildTool = detectBuildTool(targetDir);

        // 가드: SP1 로더와 동일한 상향 탐색(TiaConfigLoader.discover)으로 기존 tia.yml을 찾는다.
        // 대상 경로(targetFile) 자체도 이 탐색 범위에 포함된다.
        Path existing = TiaConfigLoader.discover(start);
        if (existing != null && !force) {
            System.err.println("ERROR: tia.yml이 이미 존재합니다: " + existing
                    + " (덮어쓰려면 --force)");
            return 1;
        }
        // --force로 진행하더라도, 발견된 기존 tia.yml이 쓰기 대상이 아닌 중간 경로(섀도잉 잔존 파일)라면
        // 삭제하지 않는 한 다음 실행(특히 서브디렉터리)에서도 계속 먼저 탐색된다 — 경로를 명시해 경고.
        if (existing != null && !existing.equals(targetFile)) {
            System.out.println(shadowFileWarningText(existing, targetFile));
        }

        // 2) 토폴로지 결정 트리(대화형) / 비대화형 플래그 동치.
        Topology resolvedTopology = resolveTopology();

        // 3) tia.yml 생성.
        String resolvedSutName = (sutName != null) ? sutName : basename(targetDir);
        String yaml = renderYaml(resolvedSutName, includeCode);

        try {
            Files.writeString(targetFile, yaml);
        } catch (Exception e) {
            System.err.println("ERROR: tia.yml 쓰기 실패: " + targetFile + " — " + e.getMessage());
            return 1;
        }

        System.out.println("tia.yml 생성됨: " + targetFile);
        // 4) 다음 단계 출력.
        printNextSteps(resolvedTopology, buildTool);
        return 0;
    }

    private enum Topology { IN_PROCESS, OUT_OF_PROCESS }

    /** init 사전 감지가 분기하는 빌드 도구 — 다음 단계 안내 문구만 바뀐다(빌드파일 자동수정은 비범위). */
    enum BuildTool { GRADLE, MAVEN, UNKNOWN }

    /** 비대화형(non-TTY)+미지정이면 picocli ParameterException(exit 2). 대화형이면 얇게 프롬프트.
     *  [FU-REQ-001] 게이트는 Tty.interactive()로 판별하되(JDK22+ 대비), readLine을 위한 Console
     *  객체 자체는 여전히 System.console()로 얻는다(비대칭 — interactive && console != null일 때만 프롬프트). */
    private Topology resolveTopology() {
        String raw = topology;
        if (raw == null) {
            Console console = System.console();
            if (!Tty.interactive() || console == null) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "Missing required option: '--topology <in-process|out-of-process>'"
                        + " — 비대화형(non-TTY) 환경에서는 필수입니다.");
            }
            raw = promptTopology(console);
        }
        return switch (raw) {
            case "in-process" -> Topology.IN_PROCESS;
            case "out-of-process" -> Topology.OUT_OF_PROCESS;
            default -> throw new CommandLine.ParameterException(spec.commandLine(),
                    "Invalid value '" + raw + "' for option '--topology'"
                    + ": in-process 또는 out-of-process 중 하나여야 합니다.");
        };
    }

    /** 대화형 결정 트리(design spec §2 step 2) — 얇은 입력 수집(로직은 resolveTopology와 공유). */
    private String promptTopology(Console console) {
        console.printf("%n%s", topologyPromptText());
        String answer = console.readLine("선택 [in-process/out-of-process]: ");
        return (answer == null) ? "" : answer.trim();
    }

    /** 토폴로지 결정 트리 안내 텍스트(질문 2개 + 혼합 토폴로지 안내) — Console 없이도 unit 검증 가능하도록 분리. */
    static String topologyPromptText() {
        return """
                토폴로지 결정 트리:
                  1) 프로덕션 코드가 테스트 스레드에서 실행되나요? (단위/슬라이스 테스트) -> in-process
                  2) 별도 서버·워커 스레드인가요? (RANDOM_PORT+RestAssured, WebSocket, @Async 등) -> out-of-process
                  둘 다 해당하면: 토폴로지는 테스트 단위 속성입니다 — 여기서는 '다음 단계'를 어느 쪽부터
                  보여줄지만 정합니다(두 모델 병용 가능, GETTING-STARTED §1 참고).
                """;
    }

    /** git 루트를 못 찾았을 때의 사전 감지 경고(design spec §2 step1) — diff 기반 기능 제약 안내. */
    static String gitMissingWarningText() {
        return "WARN: git 레포를 찾지 못했습니다 — diff 기반 기능(tia impact 등)이 제약됩니다"
                + " (git init 으로 레포를 초기화하면 이 제약이 사라집니다).";
    }

    /** --force로 진행해도 쓰기 대상이 아닌 위치에 남는 섀도잉 잔존 tia.yml에 대한 경고. */
    static String shadowFileWarningText(Path existing, Path targetFile) {
        return "WARN: 쓰기 대상(" + targetFile + ")이 아닌 위치에 남아있는 tia.yml을 발견했습니다: " + existing
                + " — 서브디렉터리에서 실행하면 이 파일이 먼저 탐색되어 계속 섀도잉할 수 있습니다. 삭제를 권장합니다.";
    }

    /** 토폴로지를 잘못 고를 때의 결과(침묵 손실 + tia convert 차단) 경고 — 대화형/비대화형 출력 모두에 포함. */
    static String silentLossWarningText() {
        return "주의: 토폴로지를 잘못 고르면 커버리지가 침묵 손실되고 tia convert가 막습니다"
                + " (자세히: GETTING-STARTED.md §1 수집 모델 결정).";
    }

    /** targetDir의 빌드 도구를 감지한다 — 다음 단계 안내 분기에만 쓰인다(빌드파일 자동수정은 비범위). */
    static BuildTool detectBuildTool(Path dir) {
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        if (Files.exists(dir.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        return BuildTool.UNKNOWN;
    }

    /** 빌드 도구별 다음 단계 한 줄 안내. gradle은 기존 문구를 그대로 유지(추가 줄 없음 → null). */
    static String buildToolHintText(BuildTool tool) {
        return switch (tool) {
            case GRADLE -> null;
            case MAVEN -> "     (Maven 프로젝트 감지됨 — pjacoco maven-plugin으로 에이전트를 배선하세요"
                    + "; 미배포 시 -javaagent 수동 배선.)";
            case UNKNOWN -> "     (빌드 도구를 자동감지하지 못했습니다 — 사용 중인 빌드 시스템에 맞게"
                    + " 에이전트를 수동 배선하세요.)";
        };
    }

    private static String basename(Path dir) {
        Path name = dir.getFileName();
        return (name != null) ? name.toString() : dir.toString();
    }

    /** YAML 단일 인용 이스케이프 — 내장 `'`는 두 번 반복(YAML 단일따옴표 스칼라 규칙). `#`·`:` 등 특수문자가
     *  섞인 값(디렉터리명 유래 sut-name, 시드 글로브)이 파싱 불가능한 tia.yml을 만들지 않도록 방어. */
    static String yamlSingleQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** SP1 스키마(version/sut-name/filters) + package-relative 글로브 함정 경고 주석 [SP3-REQ-001]. */
    private static String renderYaml(String sutName, List<String> includeCode) {
        String includeLiteral = (includeCode == null || includeCode.isEmpty())
                ? "[]"
                : "[" + String.join(", ", includeCode.stream().map(InitCommand::yamlSingleQuote).toList()) + "]";
        return """
                version: 1
                sut-name: %s

                filters:
                  code:
                    # 주의(함정): 아래 글로브는 package-relative 경로에 매칭됩니다.
                    # 예: `com/acme/**` 형태는 매칭되지만, `src/main/java/com/acme/**` 처럼
                    # 소스 루트 접두어(`src/main/java/`)가 붙은 글로브는 아무것도 매칭하지 않습니다
                    # (src/main/java/ 접두어는 매칭되지 않음).
                    include: %s
                    exclude: []
                  test:
                    include: []
                    exclude: []
                """.formatted(yamlSingleQuote(sutName), includeLiteral);
    }

    private void printNextSteps(Topology topology, BuildTool buildTool) {
        System.out.println();
        System.out.println(silentLossWarningText());
        System.out.println();
        System.out.println("다음 단계:");
        if (topology == Topology.IN_PROCESS) {
            System.out.println("  1) pjacoco in-process 에이전트(-javaagent)로 테스트를 실행해 testwise 리포트를 수집하세요.");
            System.out.println("     (자세한 절차: GETTING-STARTED.md §1 in-process)");
        } else {
            System.out.println("  1) pjacoco out-of-process(parallel-per-test-coverage) 에이전트를 SUT에 부착하세요.");
            System.out.println("     (자세한 절차: GETTING-STARTED.md §1 out-of-process)");
        }
        String buildHint = buildToolHintText(buildTool);
        if (buildHint != null) {
            System.out.println(buildHint);
        }
        System.out.println("  2) tia convert 로 수집 결과를 testwise 리포트로 변환하세요.");
        System.out.println("  3) tia index --report <testwise.json> --repo <name> --commit <sha> 로 인덱싱하세요.");
        System.out.println("  4) tia impact --commit <sha> 로 변경에 영향받는 테스트를 선별하세요.");
        System.out.println("  막히면: tia doctor 로 환경·설정 상태를 점검하세요.");
    }
}
