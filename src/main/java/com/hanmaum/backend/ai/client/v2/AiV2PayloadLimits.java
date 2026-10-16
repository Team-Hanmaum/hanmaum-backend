package com.hanmaum.backend.ai.client.v2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Explicit transport budgets, not product field limits. No production defaults. */
public record AiV2PayloadLimits(int requestBytes, int responseBytes) {
  public AiV2PayloadLimits {
    if (requestBytes < 1 || responseBytes < 1) {
      throw new IllegalArgumentException("AI v2 requires positive payload byte budgets.");
    }
  }

  static void check(long size, int limit) {
    if (size > limit) throw AiV2Json.invalid();
  }

  static byte[] read(InputStream input, long contentLength, int limit) throws IOException {
    check(contentLength, limit);
    var output = new LimitedBytes(limit);
    byte[] buffer = new byte[Math.min(8192, limit)];
    int count;
    // Read at most the remaining budget plus one sentinel byte; never buffer the excess.
    while ((count =
            input.read(buffer, 0, (int) Math.min(buffer.length, (long) limit - output.size() + 1)))
        != -1) {
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  static final class LimitedBytes extends ByteArrayOutputStream {
    private final int limit;

    LimitedBytes(int limit) {
      super(Math.min(8192, limit));
      this.limit = limit;
    }

    @Override
    public synchronized void write(int value) {
      check((long) count + 1, limit);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] value, int offset, int length) {
      check((long) count + length, limit);
      super.write(value, offset, length);
    }
  }
}
