package com.hanmaum.backend.sharing.dto;

import java.util.List;
import java.util.UUID;

public record CreateShareLinkResponse(UUID shareLinkId, String shareUrl, List<Card> cards) {
  public record Card(String itemType, boolean includesChange, String url) {}

  @Override
  public String toString() {
    return "ShareLink[id=" + shareLinkId + "]";
  }
}
