package com.hanmaum.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MemberIdentityServiceTests {
  private final SocialAccountRepository accounts = mock(SocialAccountRepository.class);
  private final MemberIdentityService identities = new MemberIdentityService(accounts);

  @ParameterizedTest
  @MethodSource("incompleteIdentities")
  void incompleteSessionIdentitiesFailBeforeDatabaseAccess(
      UUID userId, SocialProvider provider, String providerUserId) {
    assertThatThrownBy(() -> identities.requireValidUserId(userId, provider, providerUserId))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.code()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
    verifyNoInteractions(accounts);
  }

  static Stream<Arguments> incompleteIdentities() {
    UUID userId = UUID.randomUUID();
    return Stream.of(
        Arguments.of(null, SocialProvider.KAKAO, "42"),
        Arguments.of(userId, null, "42"),
        Arguments.of(userId, SocialProvider.KAKAO, null),
        Arguments.of(userId, SocialProvider.KAKAO, ""),
        Arguments.of(userId, SocialProvider.KAKAO, " "));
  }
}
