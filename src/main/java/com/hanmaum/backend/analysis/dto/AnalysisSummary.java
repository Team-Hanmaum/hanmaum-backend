package com.hanmaum.backend.analysis.dto;

import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import java.util.UUID;

public record AnalysisSummary(UUID analysisId, AnalysisStatus status) {}
