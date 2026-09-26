package com.hanmaum.backend.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
public class SessionConfig {
  @Bean
  CookieSerializer cookieSerializer(
      @Value("${server.servlet.session.cookie.name}") String name,
      @Value("${server.servlet.session.cookie.http-only}") boolean httpOnly,
      @Value("${server.servlet.session.cookie.secure}") boolean secure,
      @Value("${server.servlet.session.cookie.same-site}") String sameSite) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(name);
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(httpOnly);
    serializer.setUseSecureCookie(secure);
    serializer.setSameSite(sameSite);
    return serializer;
  }
}
