package com.hanmaum.backend.proposal.dto;

import com.hanmaum.backend.careitem.service.CareItemInput;
import com.hanmaum.backend.careitem.service.CareMutationInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;
import tools.jackson.databind.JsonNode;

public record ProposalDecisionRequest(UUID clientRequestId, List<Entry> entries) {
  public record Entry(
      UUID proposalId,
      ProposalAction action,
      String reviewToken,
      UUID targetItemId,
      @Schema(
              types = {"string", "null"},
              example = "1")
          Long expectedItemVersion,
      String leaseToken,
      JsonNode values) {
    @Override
    public String toString() {
      return "ProposalEntry[id=" + proposalId + "]";
    }
  }

  public static ProposalDecisionRequest parse(JsonNode node, boolean apply) {
    CareMutationInput.object(node, "body", Set.of("clientRequestId", "entries"));
    UUID key = CareMutationInput.uuid(node.path("clientRequestId"), "clientRequestId", true);
    JsonNode array = node.path("entries");
    if (!array.isArray() || array.isEmpty() || array.size() > 20)
      throw CareItemInput.invalid("entries", "제안을 1개 이상 20개 이하로 선택해주세요.");
    Set<UUID> ids = new HashSet<>();
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      var e = array.get(i);
      String path = "entries[" + i + "]";
      CareMutationInput.object(
          e,
          path,
          apply
              ? Set.of(
                  "proposalId",
                  "action",
                  "reviewToken",
                  "targetItemId",
                  "expectedItemVersion",
                  "leaseToken",
                  "values")
              : Set.of("proposalId", "reviewToken"));
      UUID id = CareMutationInput.uuid(e.path("proposalId"), path + ".proposalId", true);
      if (!ids.add(id)) throw CareItemInput.invalid(path + ".proposalId", "같은 제안을 두 번 선택할 수 없습니다.");
      String token = CareMutationInput.text(e.path("reviewToken"), path + ".reviewToken", true);
      if (!token.matches("[0-9a-f]{64}"))
        throw CareItemInput.invalid(path + ".reviewToken", "상세 조회의 검토 확인값을 전달해주세요.");
      ProposalAction action = null;
      if (apply) {
        try {
          action =
              ProposalAction.valueOf(
                  CareMutationInput.text(e.path("action"), path + ".action", true));
        } catch (IllegalArgumentException ex) {
          throw CareItemInput.invalid(path + ".action", "허용된 반영 동작을 입력해주세요.");
        }
      }
      entries.add(
          new Entry(
              id,
              action,
              token,
              CareMutationInput.uuid(e.path("targetItemId"), path + ".targetItemId", false),
              CareMutationInput.version(
                  e.path("expectedItemVersion"), path + ".expectedItemVersion", false),
              CareMutationInput.text(e.path("leaseToken"), path + ".leaseToken", false),
              e.has("values") ? e.get("values") : null));
    }
    return new ProposalDecisionRequest(key, List.copyOf(entries));
  }

  @Override
  public String toString() {
    return "ProposalDecisionRequest[id=" + clientRequestId + "]";
  }
}
