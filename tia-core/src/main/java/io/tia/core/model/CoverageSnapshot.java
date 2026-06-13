package io.tia.core.model;

import java.util.List;

public record CoverageSnapshot(String repo, String commitSha, List<TestCoverage> tests) {}
