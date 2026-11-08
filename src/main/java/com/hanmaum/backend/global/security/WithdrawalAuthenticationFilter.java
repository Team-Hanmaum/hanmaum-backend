package com.hanmaum.backend.global.security;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.oauth.MemberPrincipal;
import com.hanmaum.backend.user.service.MemberIdentityService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Checks this destructive account route before CSRF so old-cookie retries are always 401. */
public class WithdrawalAuthenticationFilter extends OncePerRequestFilter {
  private final MemberIdentityService identities;
  private final ObjectMapper mapper;

  public WithdrawalAuthenticationFilter(MemberIdentityService identities, ObjectMapper mapper) {
    this.identities = identities;
    this.mapper = mapper;
  }

  /** Applies the pre-CSRF identity check only to the exact account-withdrawal route. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !request.getRequestURI().equals(request.getContextPath() + "/api/users/me/withdrawals");
  }

  /** Authentication failures precede CSRF on withdrawal only; valid members still require CSRF. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof MemberPrincipal principal)) {
      error(response, CommonErrorCode.UNAUTHENTICATED);
      return;
    }
    try {
      identities.requireValidUserId(
          principal.getUserId(), principal.getProvider(), principal.getProviderUserId());
    } catch (ApiException ex) {
      error(response, CommonErrorCode.UNAUTHENTICATED);
      return;
    } catch (RuntimeException ex) {
      error(response, CommonErrorCode.INTERNAL_ERROR);
      return;
    }
    chain.doFilter(request, response);
  }

  private void error(HttpServletResponse response, CommonErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    mapper.writeValue(response.getOutputStream(), ApiResponse.error(code));
  }
}
