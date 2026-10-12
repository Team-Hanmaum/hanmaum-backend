package com.hanmaum.backend.record.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Opaque, space-bound continuation position, not authorization or a frozen list snapshot. */
@Component
public class SourceRecordCursor {
  private final CommandDigest digests;

  public SourceRecordCursor(CommandDigest digests) {
    this.digests = digests;
  }

  public String encode(UUID spaceId, Instant recordedAt, UUID recordId) {
    String payload = "v1|" + spaceId + "|" + recordedAt + "|" + recordId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
  }

  public Position decode(UUID spaceId, String value) {
    if (value == null) return null;
    try {
      if (value.isEmpty() || value.length() > 400 || !value.matches("[A-Za-z0-9_-]+")) {
        throw new IllegalArgumentException();
      }
      String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\\|", -1);
      if (parts.length != 5 || !parts[0].equals("v1") || !parts[1].equals(spaceId.toString())) {
        throw new IllegalArgumentException();
      }
      String payload = String.join("|", parts[0], parts[1], parts[2], parts[3]);
      if (!MessageDigest.isEqual(
          sign(payload).getBytes(StandardCharsets.UTF_8),
          parts[4].getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException();
      }
      Instant time = Instant.parse(parts[2]);
      UUID id = UUID.fromString(parts[3]);
      if (!time.toString().equals(parts[2]) || !id.toString().equals(parts[3]))
        throw new IllegalArgumentException();
      return new Position(time, id);
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("cursor", "목록을 새로 조회한 뒤 전달받은 커서를 사용해주세요.")));
    }
  }

  private String sign(String payload) {
    return digests.digest("SOURCE_RECORD_CURSOR", payload);
  }

  public record Position(Instant recordedAt, UUID recordId) {}
}
