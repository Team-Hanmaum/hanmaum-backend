package com.hanmaum.backend.sharing.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Domain-separated opaque locators; only hashes and a non-secret URL prefix are persisted. */
@Component
public class ShareLinkTokenService {
  private final CommandDigest digests;
  private final String prefix;

  /** Validates a deployment-controlled frontend entry point, never a caller-supplied redirect. */
  public ShareLinkTokenService(
      CommandDigest digests, @Value("${app.sharing.url-prefix}") String prefix) {
    this.digests = digests;
    URI uri;
    try {
      uri = URI.create(prefix);
    } catch (IllegalArgumentException ex) {
      throw invalidPrefix();
    }
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !prefix.endsWith("/")) throw invalidPrefix();
    this.prefix = prefix;
  }

  /** Reproduces one link's token without storing token text or a plaintext URL in receipts. */
  public String token(UUID id, UUID space) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            HexFormat.of()
                .parseHex(
                    digests.digest(
                        "HANMAUM_SHARE_LINK_TOKEN_V1", id.toString(), space.toString())));
  }

  /** Invalid locators are indistinguishable from missing links and are never echoed in errors. */
  public String hash(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Share link hashing unavailable");
    }
  }

  public String prefix() {
    return prefix;
  }

  private static IllegalStateException invalidPrefix() {
    return new IllegalStateException(
        "SHARE_URL_PREFIX must be an absolute HTTP(S) URL ending in / without credentials, query or fragment");
  }
}
