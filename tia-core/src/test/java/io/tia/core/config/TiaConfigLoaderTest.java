package io.tia.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TiaConfigLoaderTest {
    @TempDir Path root;

    private Path write(Path dir, String yaml) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("tia.yml");
        Files.writeString(f, yaml);
        return f;
    }

    @Test void explicitConfigWins_andSkipsDiscoveryEntirely() throws Exception {
        // 탐색 경로에 '깨진' tia.yml — explicit이 있으면 절대 파싱되면 안 된다 [REQ-001]
        write(root, "version: [broken");
        Path sub = root.resolve("mod");
        Path explicit = write(sub.resolve("cfg"), "version: 1\nsut-name: svc\n");
        Optional<TiaConfig> c = TiaConfigLoader.load(explicit, root);
        assertEquals("svc", c.orElseThrow().sutName());
    }

    @Test void upwardDiscoveryStopsAtGitRoot() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        write(root, "version: 1\nsut-name: from-root\n");
        Path deep = root.resolve("a/b");
        Files.createDirectories(deep);
        assertEquals("from-root", TiaConfigLoader.load(null, deep).orElseThrow().sutName());
    }

    @Test void absentYmlReturnsEmpty() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        assertTrue(TiaConfigLoader.load(null, root).isEmpty());
    }

    @Test void relativeDbResolvedAgainstYmlDir() throws Exception {
        write(root, "version: 1\ndb: .tia/tia.db\n");
        TiaConfig c = TiaConfigLoader.load(root.resolve("tia.yml"), root).orElseThrow();
        assertEquals(root.resolve(".tia/tia.db").normalize(), c.db());  // cwd 아닌 yml 위치 기준 [REQ-023]
    }

    @Test void unsupportedVersionFails() throws Exception {
        Path f = write(root, "version: 99\n");
        TiaConfigException e = assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root));
        assertTrue(e.getMessage().contains(f.toString()));
        assertTrue(e.getMessage().contains("version"));
    }

    @Test void unknownTopLevelKeyFails() throws Exception {
        Path f = write(root, "version: 1\nfiltres: {}\n");
        assertTrue(assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root)).getMessage().contains("filtres"));
    }

    @Test void malformedYamlFails() throws Exception {
        Path f = write(root, "version: [broken");
        assertTrue(assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root)).getMessage().contains(f.toString()));
    }

    @Test void badGlobInFiltersFailsFastWithFilePath() throws Exception {
        // 로드 시점에 글로브 문법을 검증해야 index를 포함한 모든 소비 명령이 fail-fast [REQ-003].
        Path f = write(root, """
                version: 1
                filters:
                  code:
                    exclude: ["[unterminated"]
                """);
        TiaConfigException e = assertThrows(TiaConfigException.class,
                () -> TiaConfigLoader.load(f, root));
        assertTrue(e.getMessage().contains(f.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("[unterminated"), e.getMessage());
    }

    @Test void filtersParsedIntoLists() throws Exception {
        Path f = write(root, """
                version: 1
                filters:
                  code:
                    include: ["com/acme/**"]
                    exclude: ["**/gen/**"]
                  test:
                    exclude: ["**/*Slow*"]
                """);
        TiaConfig c = TiaConfigLoader.load(f, root).orElseThrow();
        assertEquals(List.of("com/acme/**"), c.code().include());
        assertEquals(List.of("**/gen/**"), c.code().exclude());
        assertEquals(List.of(), c.test().include());
        assertEquals(List.of("**/*Slow*"), c.test().exclude());
    }
}
