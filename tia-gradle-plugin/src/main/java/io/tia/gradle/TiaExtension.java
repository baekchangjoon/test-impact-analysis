package io.tia.gradle;

import org.gradle.api.provider.Property;

/** {@code tia { ... }} DSL config. Managed (abstract) properties — Gradle provides the impl. */
public abstract class TiaExtension {
    public abstract Property<String> getDb();           // tia.db (baseline index)
    public abstract Property<String> getCommit();       // baseline commit
    public abstract Property<String> getRepo();         // repo name for index
    public abstract Property<String> getTestwise();     // testwise.json (index/report input)
    public abstract Property<String> getReportOut();    // report.html output
    public abstract Property<String> getSutName();      // report title
    public abstract Property<String> getJacocoDir();    // jacoco deep-link dir
    public abstract Property<String> getTestSrcRoot();  // file:// open-local links
    public abstract Property<String> getPrefixStrip();  // path-shorten prefix
    public abstract Property<String> getGitRef();       // impact diff base ref
    public abstract Property<String> getDiffFile();     // impact: precomputed unified diff (CI override)
    public abstract Property<Boolean> getStrict();      // impact: fail on no baseline
    public abstract Property<String> getCliCoordinates(); // override tia-cli artifact coords
}
