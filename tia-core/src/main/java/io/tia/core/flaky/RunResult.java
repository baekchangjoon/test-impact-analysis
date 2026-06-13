package io.tia.core.flaky;

import java.util.Map;

public record RunResult(Map<String, Boolean> passedByTest) {}
