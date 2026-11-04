package com.hanmaum.backend.careitem.dto;

import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import java.time.Instant;
import java.util.List;

public record CareItemHistoryResponse(List<Entry> items) {
  public record Entry(
      String version,
      String changeKind,
      SourceRecordAuthor changedBy,
      Instant changedAt,
      List<String> redactedFields) {}
}
