package com.hanmaum.backend.sharing.dto;

import com.hanmaum.backend.careitem.dto.CareItemDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record SharedItemsResponse(UUID spaceId, List<Entry> items) {
  public enum State {
    AVAILABLE,
    NEEDS_RECONFIRMATION,
    DELETED
  }

  @Schema(name = "SharedItemEntry")
  public record Entry(
      UUID itemId,
      State state,
      @Schema(nullable = true, description = "AVAILABLE일 때만 최신 항목. 삭제·재확인 상태는 null")
          CareItemDetail item) {}
}
