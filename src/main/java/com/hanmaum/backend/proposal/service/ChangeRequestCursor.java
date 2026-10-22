package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.service.CareItemInput;
import com.hanmaum.backend.global.command.CommandDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Signed pagination position, bound to the requested space and effective entry status filter. */
@Component
public class ChangeRequestCursor {
  private final CommandDigest digests;

  public ChangeRequestCursor(CommandDigest digests) {
    this.digests = digests;
  }

  public String encode(UUID space, String status, Instant at, UUID id) {
    String payload = prefix(space, status) + "|" + at + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((payload + "|" + sign(payload)).getBytes(StandardCharsets.UTF_8));
  }

  public Position decode(UUID space, String status, String cursor) {
    if (cursor == null) return null;
    try {
      if (cursor.isEmpty() || cursor.length() > 600 || !cursor.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException();
      String[] p =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (p.length != 6 || !String.join("|", p[0], p[1], p[2]).equals(prefix(space, status)))
        throw new IllegalArgumentException();
      String payload = String.join("|", p[0], p[1], p[2], p[3], p[4]);
      if (!MessageDigest.isEqual(
          sign(payload).getBytes(StandardCharsets.US_ASCII),
          p[5].getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
      Instant at = Instant.parse(p[3]);
      UUID id = UUID.fromString(p[4]);
      if (!at.toString().equals(p[3]) || !id.toString().equals(p[4]))
        throw new IllegalArgumentException();
      return new Position(at, id);
    } catch (IllegalArgumentException | DateTimeException ex) {
      throw CareItemInput.invalid("cursor", "같은 공간·필터로 목록을 새로 조회해주세요.");
    }
  }

  private String prefix(UUID space, String status) {
    return "v1|" + space + "|" + status;
  }

  private String sign(String payload) {
    return digests.digest("CHANGE_REQUEST_CURSOR", payload);
  }

  public record Position(Instant submittedAt, UUID requestId) {}
}
