package com.hanmaum.backend.global.command;

import java.util.Map;

public record CommandReceipt(
    String commandKind, String scopeKey, String requestDigest, Map<String, String> resultRefs) {
  public CommandReceipt {
    resultRefs = Map.copyOf(resultRefs);
  }
}
