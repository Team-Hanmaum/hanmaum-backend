package com.hanmaum.backend.ai.dto.v2;

import com.hanmaum.backend.ai.dto.CareItemType;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Use AiV2Contract at the wire boundary. Timestamp/text spelling is deliberately preserved. */
public record AiV2AnalysisRequest(
    UUID requestId,
    UUID careSpaceId,
    UUID recordId,
    String content,
    String recordedAt,
    String timezone,
    List<Candidate> candidates) {
  public AiV2AnalysisRequest {
    candidates = List.copyOf(candidates);
  }

  @Override
  public String toString() {
    return "AiV2AnalysisRequest[requestId=" + requestId + "]";
  }

  public record Candidate(
      UUID itemId,
      BigInteger version,
      CareItemType type,
      AiV2CurrentValues currentValues,
      List<Basis> basis) {
    public Candidate {
      basis = List.copyOf(basis);
    }

    @Override
    public String toString() {
      return "Candidate[itemId=" + itemId + "]";
    }
  }

  public enum BasisKind {
    SOURCE_RECORD,
    DIRECT_INPUT
  }

  public record Basis(UUID basisId, BasisKind kind, String text) {
    @Override
    public String toString() {
      return "Basis[basisId=" + basisId + "]";
    }
  }
}
