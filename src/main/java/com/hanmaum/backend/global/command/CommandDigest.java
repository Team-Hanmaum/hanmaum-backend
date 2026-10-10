package com.hanmaum.backend.global.command;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stable across restarts/instances; the key must live outside the receipt database. */
@Component
public class CommandDigest {
  private final SecretKeySpec key;

  public CommandDigest(@Value("${app.commands.digest-key}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("COMMAND_DIGEST_KEY must contain at least 32 UTF-8 bytes");
    }
    key = new SecretKeySpec(bytes, "HmacSHA256");
  }

  public String digest(String... parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
      }
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Command digest is unavailable");
    }
  }
}
