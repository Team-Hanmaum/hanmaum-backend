package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.proposal.dto.ProposalStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Scope/filter-bound position. Not authorization, a retention TTL, or a snapshot of all rows. */
@Component
public class ProposalCursor {
  private final CommandDigest digests;

  public ProposalCursor(CommandDigest digests) {
    this.digests = digests;
  }

  public String encode(UUID space, UUID record, ProposalStatus status, Instant time, UUID id) {
    String payload = prefix(space, record, status) + "|" + time + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
  }

  public Position decode(UUID space, UUID record, ProposalStatus status, String cursor) {
    if (cursor == null) return null;
    try {
      if (cursor.isEmpty() || cursor.length() > 600 || !cursor.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 7
          || !String.join("|", parts[0], parts[1], parts[2], parts[3])
              .equals(prefix(space, record, status))) throw new IllegalArgumentException();
      String payload = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
      if (!MessageDigest.isEqual(
          sign(payload).getBytes(StandardCharsets.UTF_8),
          parts[6].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
      Instant time = Instant.parse(parts[4]);
      UUID id = UUID.fromString(parts[5]);
      if (!time.toString().equals(parts[4]) || !id.toString().equals(parts[5]))
        throw new IllegalArgumentException();
      return new Position(time, id);
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("cursor", "같은 공간·필터로 목록을 새로 조회해주세요.")));
    }
  }

  private String prefix(UUID space, UUID record, ProposalStatus status) {
    return "v1|" + space + "|" + (record == null ? "*" : record) + "|" + status.name();
  }

  private String sign(String payload) {
    return digests.digest("PROPOSAL_CURSOR", payload);
  }

  public record Position(Instant createdAt, UUID proposalId) {}
}
