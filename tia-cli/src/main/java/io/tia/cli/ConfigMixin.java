package io.tia.cli;

import io.tia.core.config.TiaConfig;
import io.tia.core.config.TiaConfigLoader;
import io.tia.core.filter.FilterSet;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** --config / --search-root 만. 모든 소비 커맨드(index 포함)가 사용. */
public class ConfigMixin {
    @Option(names = "--config", description = "tia.yml 경로 (미지정 시 상향 탐색)") Path config;
    @Option(names = "--search-root", hidden = true,
            description = "탐색 시작 디렉터리(테스트 시임; 기본 cwd)") Path searchRoot;

    /** TiaConfigException은 호출측이 잡아 exit 1 [REQ-003]. */
    public TiaConfig loadConfig() {
        Path start = (searchRoot != null) ? searchRoot : Path.of("").toAbsolutePath();
        return TiaConfigLoader.load(config, start).orElse(TiaConfig.empty());
    }

    /** 플래그는 tia.yml의 해당 목록을 대체(병합 아님), null 믹스인은 "그 축의 플래그 없음" [REQ-002]. */
    static FilterSet filtersOf(TiaConfig cfg, CodeFilterMixin code, TestFilterMixin test) {
        return FilterSet.of(
                (code != null && code.includeCode != null) ? code.includeCode : cfg.code().include(),
                (code != null && code.excludeCode != null) ? code.excludeCode : cfg.code().exclude(),
                (test != null && test.includeTest != null) ? test.includeTest : cfg.test().include(),
                (test != null && test.excludeTest != null) ? test.excludeTest : cfg.test().exclude());
    }
}
