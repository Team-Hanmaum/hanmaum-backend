package com.hanmaum.backend.ai.dto.v2;

import com.hanmaum.backend.ai.dto.CareItemType;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validated review proposals only; no CareItem mutation or public response mapping. */
public record AiV2AnalysisResponse(UUID requestId, List<Suggestion> suggestions) {
  public AiV2AnalysisResponse {
    suggestions = List.copyOf(suggestions);
  }

  @Override
  public String toString() {
    return "AiV2AnalysisResponse[requestId=" + requestId + "]";
  }

  public enum Operation {
    CREATE,
    UPDATE,
    RESOLVE,
    LINK_EVIDENCE
  }

  public enum RelationKind {
    NEW,
    RELATED,
    DUPLICATE,
    CONFLICT,
    UNDETERMINED
  }

  public enum ReviewReason {
    TARGET_AMBIGUOUS,
    TARGET_NOT_FOUND,
    VALUE_UNCLEAR,
    CONFLICT,
    ASSIGNEE_UNRESOLVED
  }

  public record Suggestion(
      Operation operation,
      CareItemType type,
      UUID targetItemId,
      BigInteger expectedVersion,
      List<UUID> candidateItemIds,
      String title,
      String evidence,
      Map<String, String> changes,
      List<String> clearFields,
      RelationKind relationKind,
      List<ReviewReason> reviewReasons,
      List<BasisReference> basisReferences) {
    public Suggestion {
      candidateItemIds = List.copyOf(candidateItemIds);
      changes = Map.copyOf(changes);
      clearFields = List.copyOf(clearFields);
      reviewReasons = List.copyOf(reviewReasons);
      basisReferences = List.copyOf(basisReferences);
    }

    @Override
    public String toString() {
      return "Suggestion[operation=" + operation + "]";
    }
  }

  public record BasisReference(UUID itemId, List<UUID> basisIds) {
    public BasisReference {
      basisIds = List.copyOf(basisIds);
    }
  }
}
