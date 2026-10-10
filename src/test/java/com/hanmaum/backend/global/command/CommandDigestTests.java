package com.hanmaum.backend.global.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandDigestTests {
  @Test
  void isStableAcrossInstancesAndBindsEveryRequestPartWithoutDelimiterAmbiguity() {
    String key = "test-only-command-digest-key-not-for-production";
    var first = new CommandDigest(key);
    var restarted = new CommandDigest(key);
    assertThat(first.digest("actor", "request", "CREATE", "SELF", "엄마"))
        .isEqualTo(restarted.digest("actor", "request", "CREATE", "SELF", "엄마"))
        .isNotEqualTo(first.digest("other", "request", "CREATE", "SELF", "엄마"))
        .isNotEqualTo(first.digest("actor", "request", "CREATE", "SELF", "아빠"))
        .isNotEqualTo(
            new CommandDigest("another-test-only-secret-at-least-32-bytes")
                .digest("actor", "request", "CREATE", "SELF", "엄마"));
    assertThat(first.digest("ab", "c")).isNotEqualTo(first.digest("a", "bc"));
  }

  @Test
  void rejectsMissingOrShortSecretsWithoutIncludingThemInErrors() {
    assertThatThrownBy(() -> new CommandDigest("short-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("short-secret");
  }
}
