package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvitationTokenService {
  private static final int NONCE_BYTES = 12;
  private final SecureRandom random = new SecureRandom();
  private final SecretKeySpec key;
  private final String urlPrefix;

  public InvitationTokenService(
      @Value("${app.invitations.encryption-key}") String secret,
      @Value("${app.invitations.url-prefix}") String prefix) {
    try {
      byte[] bytes = Base64.getDecoder().decode(secret);
      if (bytes.length != 32) throw new IllegalArgumentException();
      key = new SecretKeySpec(bytes, "AES");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "INVITATION_ENCRYPTION_KEY must be Base64 of 32 random bytes");
    }
    URI uri;
    try {
      uri = URI.create(prefix);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("INVITATION_URL_PREFIX must be an absolute HTTP(S) URL");
    }
    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !prefix.endsWith("/")) {
      throw new IllegalStateException(
          "INVITATION_URL_PREFIX must be an HTTP(S) URL ending in / without credentials, query or fragment");
    }
    urlPrefix = prefix;
  }

  public String generate() {
    byte[] token = new byte[32];
    random.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  public String hash(String token) {
    // This is the generated token encoding, not a user-facing input length policy.
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Invitation hashing is unavailable");
    }
  }

  public String sealUrl(UUID invitationId, String token) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, invitationId, nonce);
      byte[] encrypted = cipher.doFinal((urlPrefix + token).getBytes(StandardCharsets.UTF_8));
      return "v1."
          + Base64.getEncoder()
              .encodeToString(
                  ByteBuffer.allocate(nonce.length + encrypted.length)
                      .put(nonce)
                      .put(encrypted)
                      .array());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Invitation encryption failed");
    }
  }

  public String openUrl(UUID invitationId, String sealed) {
    try {
      if (sealed == null || !sealed.startsWith("v1.")) throw new IllegalArgumentException();
      byte[] combined = Base64.getDecoder().decode(sealed.substring(3));
      if (combined.length <= NONCE_BYTES + 16) throw new IllegalArgumentException();
      byte[] nonce = new byte[NONCE_BYTES];
      ByteBuffer.wrap(combined).get(nonce);
      return new String(
          cipher(Cipher.DECRYPT_MODE, invitationId, nonce)
              .doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES),
          StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      // Neither the encrypted payload nor a token is exposed in the error or log.
      throw new IllegalStateException("Invitation decryption failed");
    }
  }

  private Cipher cipher(int mode, UUID id, byte[] nonce) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("hanmaum:invitation:v1:" + id).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }
}
