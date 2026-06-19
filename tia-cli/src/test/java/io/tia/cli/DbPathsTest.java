package io.tia.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbPathsTest {

    @Test
    @DisplayName("REQ-006: git-common-dir이 있으면 <common>/tia/tia.db")
    void gitCommonDirPath(@TempDir Path common) {
        Path p = DbPaths.resolveDefault(k -> null, common);
        assertEquals(common.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG_CACHE_HOME 설정 시 <xdg>/tia/tia.db")
    void xdgFallbackWhenEnvSet(@TempDir Path xdg) {
        Map<String, String> env = Map.of("XDG_CACHE_HOME", xdg.toString());
        Path p = DbPaths.resolveDefault(env::get, null);
        assertEquals(xdg.resolve("tia").resolve("tia.db"), p);
    }

    @Test
    @DisplayName("REQ-007: git-common-dir 없고 XDG 미설정 시 ~/.cache/tia/tia.db")
    void homeCacheFallbackWhenNoXdg() {
        Path p = DbPaths.resolveDefault(k -> null, null);
        Path expected = Path.of(System.getProperty("user.home"), ".cache", "tia", "tia.db");
        assertEquals(expected, p);
    }
}
