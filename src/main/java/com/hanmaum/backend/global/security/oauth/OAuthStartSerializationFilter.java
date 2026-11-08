package com.hanmaum.backend.global.security.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.filter.OncePerRequestFilter;

/** Serializes starts through JDBC session commit in the current single-BE deployment. */
public final class OAuthStartSerializationFilter extends OncePerRequestFilter {
  private final ReentrantLock starts = new ReentrantLock(true);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Spring's authorization resolver matches this path independently of HTTP method.
    return !request.getRequestURI().startsWith(request.getContextPath() + "/oauth2/authorization/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // This must wrap SessionRepositoryFilter, not only saveAuthorizationRequest: otherwise
    // parallel requests read stale counts and JDBC commits happen after releasing the lock.
    // A global start lock also covers session-ID rotation; it never merges anonymous sessions.
    starts.lock();
    try {
      chain.doFilter(request, response);
    } finally {
      starts.unlock();
    }
  }
}
