package com.hanmaum.backend.global.security;

import com.hanmaum.backend.global.security.oauth.MemberAuthorizedClientRepository;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.security.web.authentication.logout.*;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalSessionCleanup {
  private final MemberAuthorizedClientRepository clients;

  public WithdrawalSessionCleanup(MemberAuthorizedClientRepository clients) {
    this.clients = clients;
  }

  /** Clears request-local state only after account/session deletion successfully committed. */
  public void clear(UUID user, HttpServletRequest request, HttpServletResponse response) {
    clients.removeMember(user);
    new SecurityContextLogoutHandler().logout(request, response, null);
    new CookieClearingLogoutHandler("HANMAUM_SESSION").logout(request, response, null);
  }
}
