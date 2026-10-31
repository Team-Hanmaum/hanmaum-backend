package com.hanmaum.backend.careitem.dto;

import java.util.List;

public record CareItemSection(List<CareItemSummary> items, long totalCount) {}
