package com.hanmaum.backend.analysis.dto;

import com.hanmaum.backend.carespace.dto.SpaceVersionDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RetryAnalysisRequest(
    @NotNull UUID failedAnalysisId,
    @NotNull
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        @JsonDeserialize(using = SpaceVersionDeserializer.class)
        String expectedRecordVersion,
    @NotNull UUID clientRequestId) {}
