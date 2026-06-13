package io.tia.core.flaky;

import java.util.List;

public record FlakyReport(double ratio, List<String> flakyTests, int totalTests) {}
