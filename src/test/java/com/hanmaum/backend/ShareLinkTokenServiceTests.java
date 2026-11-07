package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.sharing.service.ShareLinkTokenService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShareLinkTokenServiceTests {
  private static final String KEY = "share-test-only-key-over-thirty-two-bytes";

  @Test
  void tokensAreStableAcrossInstancesAndPrefixChangesButBoundToSecretIdAndSpace() {
    UUID id = UUID.randomUUID(), space = UUID.randomUUID();
    var service =
        new ShareLinkTokenService(new CommandDigest(KEY), "https://hanmaum.example/share/");
    String token = service.token(id, space);
    assertThat(token).matches("[A-Za-z0-9_-]{43}");
    assertThat(service.hash(token)).matches("[0-9a-f]{64}");
    assertThat(
            new ShareLinkTokenService(new CommandDigest(KEY), "https://other.example/open/")
                .token(id, space))
        .isEqualTo(token);
    assertThat(service.token(UUID.randomUUID(), space)).isNotEqualTo(token);
    assertThat(service.token(id, UUID.randomUUID())).isNotEqualTo(token);
    assertThat(
            new ShareLinkTokenService(new CommandDigest(KEY + "rotated"), service.prefix())
                .token(id, space))
        .isNotEqualTo(token);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/share/",
        "https://example.com/share",
        "javascript:alert(1)",
        "https://user:password@example.com/share/",
        "https://example.com/share/?query=1/",
        "https://example.com/share/#fragment/",
        "file:///tmp/share/"
      })
  void configurationCannotIntroduceAnUnvalidatedRedirectOrIncludeCredentials(String prefix) {
    assertThatThrownBy(() -> new ShareLinkTokenService(new CommandDigest(KEY), prefix))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(prefix);
  }

  @Test
  void invalidTokensAreNotEchoed() {
    var service = new ShareLinkTokenService(new CommandDigest(KEY), "http://localhost:5173/share/");
    for (String token :
        new String[] {"", "sensitive-invalid-token", "a".repeat(44), "a".repeat(42) + "%"}) {
      assertThatThrownBy(() -> service.hash(token))
          .isInstanceOfSatisfying(
              ApiException.class,
              ex -> assertThat(ex.code().code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
    assertThatThrownBy(() -> service.hash(null)).isInstanceOf(ApiException.class);
  }
}
