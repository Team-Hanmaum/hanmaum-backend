package com.hanmaum.backend.global.security;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberPrincipal;
import com.hanmaum.backend.user.service.MemberIdentityService;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
  private final MemberIdentityService identities;
  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  public CurrentUserArgumentResolver(MemberIdentityService identities) {
    this.identities = identities;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentUser.class);
  }

  @Override
  public AuthenticatedUser resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer container,
      NativeWebRequest request,
      WebDataBinderFactory binderFactory) {
    if (parameter.getParameterType() != AuthenticatedUser.class) {
      throw new IllegalStateException("@CurrentUser requires an AuthenticatedUser parameter");
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!trustResolver.isAuthenticated(authentication)
        || !(authentication.getPrincipal() instanceof MemberPrincipal principal)) {
      throw new ApiException(CommonErrorCode.UNAUTHENTICATED);
    }
    var userId =
        identities.requireValidUserId(
            principal.getUserId(), principal.getProvider(), principal.getProviderUserId());
    return new AuthenticatedUser(userId);
  }
}
