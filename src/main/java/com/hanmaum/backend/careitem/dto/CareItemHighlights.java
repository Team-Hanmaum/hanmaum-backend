package com.hanmaum.backend.careitem.dto;

public record CareItemHighlights(
    CareItemSection upcomingSchedules,
    long pastScheduleCount,
    CareItemSection remainingTasks,
    CareItemSection unconfirmedQuestions) {}
