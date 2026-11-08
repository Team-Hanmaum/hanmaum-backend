package com.hanmaum.backend.global.security;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.oauth.FrontendAuthorizationRequestResolver;
import com.hanmaum.backend.global.security.oauth.HanmaumOAuth2UserService;
import com.hanmaum.backend.global.security.oauth.HanmaumOidcUserService;
import com.hanmaum.backend.global.security.oauth.OAuthLoginFailureHandler;
import com.hanmaum.backend.global.security.oauth.OAuthStartSerializationFilter;
import com.hanmaum.backend.global.security.oauth.StateAuthorizationRequestRepository;
import com.hanmaum.backend.user.service.MemberIdentityService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
  @Bean
  FilterRegistrationBean<OAuthStartSerializationFilter> oauthStartSerializationFilter() {
    var registration = new FilterRegistrationBean<>(new OAuthStartSerializationFilter());
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
    return registration;
  }

  /**
   * Builds the session/CSRF chain, checking withdrawal identity before CSRF for old-cookie 401s.
   * Existing OAuth redirects and the authentication order of other routes remain unchanged.
   */
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityProperties properties,
      ObjectMapper mapper,
      ObjectProvider<ClientRegistrationRepository> clients,
      HanmaumOAuth2UserService oauth2Users,
      HanmaumOidcUserService oidcUsers,
      OAuthLoginFailureHandler oauthLoginFailureHandler,
      MemberIdentityService memberIdentities,
      Clock clock)
      throws Exception {
    http.addFilterBefore(
        new WithdrawalAuthenticationFilter(memberIdentities, mapper),
        org.springframework.security.web.csrf.CsrfFilter.class);
    http.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .authorizeHttpRequests(
            authorize -> {
              authorize
                  .requestMatchers(
                      HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/api/auth/csrf")
                  .permitAll();
              authorize.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll();
              if (properties.publicDocs()) {
                authorize
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll();
              }
              authorize.anyRequest().authenticated();
            })
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeError(response, mapper, CommonErrorCode.UNAUTHENTICATED))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeError(response, mapper, CommonErrorCode.FORBIDDEN)))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/auth/logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("HANMAUM_SESSION")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(204)));

    // The oauth profile is opt-in until provider credentials are configured.
    var registrations = clients.getIfAvailable();
    if (registrations != null) {
      var requests = new StateAuthorizationRequestRepository(clock);
      var resolver =
          new FrontendAuthorizationRequestResolver(registrations, properties.loginSuccessUrl());
      AuthenticationFailureHandler failureHandler =
          (request, response, exception) -> {
            requests.clear(request, response);
            oauthLoginFailureHandler.onAuthenticationFailure(request, response, exception);
          };
      http.oauth2Login(
          oauth ->
              oauth
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint
                              .authorizationRequestResolver(resolver)
                              .authorizationRequestRepository(requests))
                  .withObjectPostProcessor(
                      new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                        @Override
                        public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(
                            O filter) {
                          filter.setAuthenticationFailureHandler(failureHandler);
                          return filter;
                        }
                      })
                  .userInfoEndpoint(
                      userInfo -> userInfo.userService(oauth2Users).oidcUserService(oidcUsers))
                  .successHandler(
                      (request, response, authentication) ->
                          response.sendRedirect(
                              requests.takeSuccessUrl(request, properties.loginSuccessUrl())))
                  .failureHandler(failureHandler));
    }
    // Keep Spring Security's session-based CSRF protection enabled.
    return http.build();
  }

  private CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(properties.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private static void writeError(HttpServletResponse response, ObjectMapper mapper, ErrorCode code)
      throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(), ApiResponse.error(code));
  }
}
