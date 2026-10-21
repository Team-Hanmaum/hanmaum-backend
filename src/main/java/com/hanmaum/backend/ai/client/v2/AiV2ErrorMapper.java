package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.AiV2Json.*;

import com.hanmaum.backend.ai.code.AiFailureCode;
import java.util.Map;

/** Exact HTTP/code allowlist. Upstream messages are checked for shape, then discarded. */
public final class AiV2ErrorMapper {
  private record ErrorKey(int status, String code) {}

  private static final Map<ErrorKey, AiFailureCode> CODES =
      Map.ofEntries(
          Map.entry(new ErrorKey(400, "REQUEST_ID_MISMATCH"), AiFailureCode.AI_INTEGRATION_ERROR),
          Map.entry(new ErrorKey(401, "UNAUTHORIZED"), AiFailureCode.AI_INTEGRATION_ERROR),
          Map.entry(new ErrorKey(422, "INVALID_REQUEST"), AiFailureCode.AI_INTEGRATION_ERROR),
          Map.entry(
              new ErrorKey(409, "REQUEST_ALREADY_RECEIVED"), AiFailureCode.AI_DUPLICATE_REQUEST),
          Map.entry(new ErrorKey(409, "REQUEST_ID_REUSED"), AiFailureCode.AI_REQUEST_ID_REUSED),
          Map.entry(new ErrorKey(502, "INVALID_AI_RESPONSE"), AiFailureCode.AI_INVALID_RESPONSE),
          Map.entry(new ErrorKey(502, "AI_UNAVAILABLE"), AiFailureCode.AI_SERVICE_UNAVAILABLE),
          Map.entry(new ErrorKey(503, "AI_NOT_CONFIGURED"), AiFailureCode.AI_INTEGRATION_ERROR),
          Map.entry(
              new ErrorKey(503, "RECEIPT_STORE_UNAVAILABLE"),
              AiFailureCode.AI_RECEIPT_STORE_UNAVAILABLE),
          Map.entry(new ErrorKey(504, "AI_TIMEOUT"), AiFailureCode.AI_TIMEOUT),
          Map.entry(new ErrorKey(413, "REQUEST_TOO_LARGE"), AiFailureCode.AI_INTEGRATION_ERROR),
          Map.entry(
              new ErrorKey(503, "REQUEST_COMPARISON_UNAVAILABLE"),
              AiFailureCode.AI_RECEIPT_STORE_UNAVAILABLE),
          Map.entry(new ErrorKey(503, "AI_OVERLOADED"), AiFailureCode.AI_SERVICE_UNAVAILABLE));

  public AiFailureCode map(int status, byte[] raw, int maxBytes) {
    try {
      var node = read(raw, maxBytes);
      keys(node, "code", "message");
      String code = nonBlank(node.get("code"));
      nonBlank(node.get("message"));
      if (AiV2SizeRules.stringUnits(node, 50_000) > 50_000)
        return AiFailureCode.AI_INVALID_RESPONSE;
      return CODES.getOrDefault(new ErrorKey(status, code), AiFailureCode.AI_SERVICE_UNAVAILABLE);
    } catch (RuntimeException exception) {
      return AiFailureCode.AI_SERVICE_UNAVAILABLE;
    }
  }
}
