package com.hanmaum.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberPrincipal;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.service.MemberIdentityService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserArgumentResolverTests {
  private final MemberIdentityService identities = mock(MemberIdentityService.class);
  private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver(identities);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void leavesUnannotatedControllerParametersToOtherResolvers() throws Exception {
    assertThat(resolver.supportsParameter(parameter("current", AuthenticatedUser.class))).isTrue();
    assertThat(resolver.supportsParameter(parameter("ordinary", String.class))).isFalse();
    verifyNoInteractions(identities);
  }

  @ParameterizedTest
  @MethodSource("unusableAuthentications")
  void rejectsMissingAnonymousUnauthenticatedAndLegacyIdentities(Authentication authentication)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var parameter = parameter("current", AuthenticatedUser.class);
    assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> assertThat(exception.code()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
    verifyNoInteractions(identities);
  }

  static Stream<Arguments> unusableAuthentications() {
    var member = mock(MemberPrincipal.class);
    return Stream.of(
        Arguments.of((Authentication) null),
        Arguments.of(
            new AnonymousAuthenticationToken(
                "test", member, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))),
        Arguments.of(UsernamePasswordAuthenticationToken.unauthenticated(member, null)),
        Arguments.of(
            UsernamePasswordAuthenticationToken.authenticated("legacy-user", null, List.of())));
  }

  @Test
  void returnsOnlyTheServiceIdAfterValidation() throws Exception {
    var member = mock(MemberPrincipal.class);
    UUID userId = UUID.randomUUID();
    when(member.getUserId()).thenReturn(userId);
    when(member.getProvider()).thenReturn(SocialProvider.KAKAO);
    when(member.getProviderUserId()).thenReturn("42");
    when(identities.requireValidUserId(userId, SocialProvider.KAKAO, "42")).thenReturn(userId);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(member, null, List.of()));

    var result =
        resolver.resolveArgument(parameter("current", AuthenticatedUser.class), null, null, null);
    assertThat(result).isEqualTo(new AuthenticatedUser(userId));
  }

  @Test
  void incorrectAnnotationTypeIsAProgrammingErrorRatherThanAnAuthenticationFailure()
      throws Exception {
    var parameter = parameter("incorrect", String.class);
    assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(identities);
  }

  private MethodParameter parameter(String method, Class<?> parameterType) throws Exception {
    return new MethodParameter(ExampleController.class.getDeclaredMethod(method, parameterType), 0);
  }

  static class ExampleController {
    void current(@CurrentUser AuthenticatedUser user) {}

    void ordinary(String value) {}

    void incorrect(@CurrentUser String value) {}
  }
}
