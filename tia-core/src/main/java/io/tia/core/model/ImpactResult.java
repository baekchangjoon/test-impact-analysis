package io.tia.core.model;

import java.util.List;

public record ImpactResult(List<ImpactedTest> impacted, boolean conservativeSelectAll, List<String> reasons) {}
