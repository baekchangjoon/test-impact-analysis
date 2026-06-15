package io.tia.cli;

import picocli.CommandLine;

import java.io.InputStream;
import java.util.Properties;

/**
 * D1: single source of truth for the CLI version. Reads {@code /tia-version.properties}
 * (generated from the Gradle {@code project.version}), so {@code tia --version} always
 * matches the released build — no hardcoded literal to drift.
 */
public final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
        try (InputStream in = VersionProvider.class.getResourceAsStream("/tia-version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return new String[] { "tia " + p.getProperty("version", "unknown") };
        }
    }
}
