package com.hanmaum.backend.carespace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InvitationTokenServiceTests {
  private static final String KEY = "dGVzdC1vbmx5LWludml0YXRpb24ta2V5LTMyYnl0ZXM=";
  private final InvitationTokenService tokens =
      new InvitationTokenService(KEY, "https://example.test/invitations/");

  @Test
  void randomTokensAndAuthenticatedEncryptionRetainTheOriginalUrl() {
    var id = UUID.randomUUID();
    var token = tokens.generate();
    assertThat(token).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(tokens.generate());
    assertThat(tokens.hash(token)).matches("[0-9a-f]{64}").isEqualTo(tokens.hash(token));
    var sealed = tokens.sealUrl(id, token);
    assertThat(sealed)
        .doesNotContain(token)
        .doesNotContain("example.test")
        .isNotEqualTo(tokens.sealUrl(id, token));
    assertThat(tokens.openUrl(id, sealed)).isEqualTo("https://example.test/invitations/" + token);
    var changedPrefix = new InvitationTokenService(KEY, "https://new.example.test/invite/");
    assertThat(changedPrefix.openUrl(id, sealed)).isEqualTo(tokens.openUrl(id, sealed));
  }

  @Test
  void rejectsTamperingRowSwapsAndWrongKeysWithoutExposingPayloads() {
    var id = UUID.randomUUID();
    var sealed = tokens.sealUrl(id, tokens.generate());
    assertThatThrownBy(() -> tokens.openUrl(UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invitation decryption failed")
        .hasNoCause();
    byte[] changed = Base64.getDecoder().decode(sealed.substring(3));
    changed[changed.length - 1] ^= 1;
    assertThatThrownBy(
            () -> tokens.openUrl(id, "v1." + Base64.getEncoder().encodeToString(changed)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invitation decryption failed");
    var otherKey =
        new InvitationTokenService(
            Base64.getEncoder().encodeToString(new byte[32]), "https://example.test/");
    assertThatThrownBy(() -> otherKey.openUrl(id, sealed))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"bad", "v2.abc", "v1.abc", "v1.%%%"})
  void rejectsInvalidEncryptedPayloads(String sealed) {
    assertThatThrownBy(() -> tokens.openUrl(UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invitation decryption failed");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"bad", "../../", "12345678901234567890123456789012345678901234+"})
  void invalidTokenIsAnUnavailableInvitation(String token) {
    assertThatThrownBy(() -> tokens.hash(token))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo(CareSpaceErrorCode.INVITATION_UNAVAILABLE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-base64", "c2hvcnQ="})
  void failsFastForMissingOrInvalidKeys(String key) {
    assertThatThrownBy(() -> new InvitationTokenService(key, "https://example.test/"))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/invite/",
        "https://example.test/invite",
        "file:///tmp/",
        "https://user:password@example.test/",
        "https://example.test/?a=/",
        "https://example.test/#/"
      })
  void failsFastForInvalidPrefixes(String prefix) {
    assertThatThrownBy(() -> new InvitationTokenService(KEY, prefix))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
  }
}
