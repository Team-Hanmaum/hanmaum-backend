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
    return parse(node, apply, false);
  }

  public static ProposalDecisionRequest submission(JsonNode node) {
    return parse(node, true, true);
  }

  private static ProposalDecisionRequest parse(JsonNode node, boolean apply, boolean submission) {
    CareMutationInput.object(node, "body", Set.of("clientRequestId", "entries"));
    UUID key = CareMutationInput.uuid(node.path("clientRequestId"), "clientRequestId", true);
    JsonNode array = node.path("entries");
    if (!array.isArray() || array.isEmpty() || array.size() > 20)
      throw CareItemInput.invalid("entries", "제안을 1개 이상 20개 이하로 선택해주세요.");
    Set<UUID> ids = new HashSet<>();
    List<Entry> entries = new ArrayList<>();
    Set<String> allowed =
        apply
            ? new HashSet<>(
                Set.of(
                    "proposalId",
                    "action",
                    "reviewToken",
                    "targetItemId",
                    "expectedItemVersion",
                    "leaseToken",
                    "values"))
            : Set.of("proposalId", "reviewToken");
    if (submission) allowed.add("sourceType");
    for (int i = 0; i < array.size(); i++) {
      var e = array.get(i);
      String path = "entries[" + i + "]";
      boolean direct = false;
      if (submission && e.has("sourceType")) {
        String source = CareMutationInput.text(e.get("sourceType"), path + ".sourceType", true);
        if (!Set.of("AI_PROPOSAL", "DIRECT_INPUT").contains(source))
          throw CareItemInput.invalid(path + ".sourceType", "요청 출처를 확인해주세요.");
        direct = source.equals("DIRECT_INPUT");
      }
      CareMutationInput.object(e, path, allowed);
      UUID id = CareMutationInput.uuid(e.path("proposalId"), path + ".proposalId", !direct);
      if (direct && (id != null || e.hasNonNull("reviewToken")))
        throw CareItemInput.invalid(path, "직접 입력에는 제안 ID·제안 검토 토큰을 전달하지 않습니다.");
      if (id != null && !ids.add(id))
        throw CareItemInput.invalid(path + ".proposalId", "같은 제안을 두 번 선택할 수 없습니다.");
      String token = CareMutationInput.text(e.path("reviewToken"), path + ".reviewToken", !direct);
      if (!direct && !token.matches("[0-9a-f]{64}"))
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
      if (direct && action != ProposalAction.CREATE_ITEM && action != ProposalAction.UPDATE_ITEM)
        throw CareItemInput.invalid(path + ".action", "직접 입력은 생성·수정만 지원합니다.");
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
