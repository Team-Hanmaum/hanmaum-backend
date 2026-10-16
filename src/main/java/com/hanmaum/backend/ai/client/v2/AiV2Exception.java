package com.hanmaum.backend.ai.client.v2;

import com.hanmaum.backend.ai.code.AiFailureCode;

/** Never retains upstream bodies, parser details, source content or exception causes. */
public final class AiV2Exception extends RuntimeException {
  private final AiFailureCode failureCode;

  public AiV2Exception(AiFailureCode failureCode) {
    super(failureCode.message());
    this.failureCode = failureCode;
  }

  public AiFailureCode failureCode() {
    return failureCode;
  }
}
