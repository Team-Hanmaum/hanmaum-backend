package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.AppUserRepository;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OAuthMemberLoginIntegrationTests.ProviderConfig.class})
class OAuthMemberLoginIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired FakeProvider provider;
  @Autowired AppUserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean SocialAccountRepository socialAccounts;

  @BeforeEach
  void resetIsolatedDataAndProvider() {
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM spring_session");
    provider.name = "첫 이름";
    provider.kakaoId = 42L;
    provider.googleUserInfoSubject = "42";
    provider.userInfoFails = false;
  }

  @ParameterizedTest
  @EnumSource(SocialProvider.class)
  void callbackMapsMemberAndJdbcSessionSurvivesTheNextRequest(SocialProvider socialProvider)
      throws Exception {
    String registration = socialProvider.name().toLowerCase(java.util.Locale.ROOT);
    var first = login(registration);
    assertRedirect(first);
    Cookie cookie = first.getResponse().getCookie("HANMAUM_SESSION");
    assertThat(cookie).isNotNull();
    mvc.perform(get("/api/test/login-probe").cookie(cookie)).andExpect(status().isOk());

    var userBefore =
        socialAccounts
            .findByProviderAndProviderUserId(socialProvider, "42")
            .orElseThrow()
            .getUser();
    assertThat(userBefore.getDisplayName()).isEqualTo("첫 이름");
    provider.name = "제공자에서 바뀐 이름";
    assertRedirect(login(registration));
    var userAfter =
        socialAccounts
            .findByProviderAndProviderUserId(socialProvider, "42")
            .orElseThrow()
            .getUser();
    assertThat(userAfter.getId()).isEqualTo(userBefore.getId());
    assertThat(userAfter.getDisplayName()).isEqualTo("첫 이름");
    assertThat(userAfter.getUpdatedAt()).isEqualTo(userBefore.getUpdatedAt());
    assertThat(users.count()).isEqualTo(1);
    assertThat(socialAccounts.count()).isEqualTo(1);
  }

  @Test
  void equalProviderIdsAndEmailsDoNotLinkGoogleAndKakaoAccounts() throws Exception {
    assertRedirect(login("google"));
    assertRedirect(login("kakao"));
    var google =
        socialAccounts
            .findByProviderAndProviderUserId(SocialProvider.GOOGLE, "42")
            .orElseThrow()
            .getUser()
            .getId();
    var kakao =
        socialAccounts
            .findByProviderAndProviderUserId(SocialProvider.KAKAO, "42")
            .orElseThrow()
            .getUser()
            .getId();
    assertThat(google).isNotEqualTo(kakao);
    assertThat(users.count()).isEqualTo(2);
  }

  @ParameterizedTest
  @EnumSource(SocialProvider.class)
  void absentProviderNameDoesNotPreventLogin(SocialProvider socialProvider) throws Exception {
    provider.name = null;
    assertRedirect(login(socialProvider.name().toLowerCase(java.util.Locale.ROOT)));
    assertThat(users.findAll())
        .singleElement()
        .satisfies(user -> assertThat(user.getDisplayName()).isNull());
  }

  @Test
  void rejectsInvalidKakaoIdentityWithoutCreatingMembers() throws Exception {
    provider.kakaoId = 1.5;
    assertFailure(login("kakao"));
    assertThat(users.count()).isZero();
    assertThat(socialAccounts.count()).isZero();
  }

  @Test
  void rejectsMismatchedGoogleUserInfoBeforeMemberMapping() throws Exception {
    provider.googleUserInfoSubject = "different-subject";
    assertFailure(login("google"));
    assertThat(users.count()).isZero();
  }

  @Test
  void providerFailureDoesNotCreateMembersOrAuthenticateTheSession() throws Exception {
    provider.userInfoFails = true;
    assertFailure(login("kakao"));
    assertThat(users.count()).isZero();
  }

  @Test
  void databaseFailureRollsBackAndReturnsTheExistingSanitizedLoginError() throws Exception {
    doThrow(new DataAccessResourceFailureException("PRIVATE-PROVIDER-DATA"))
        .when(socialAccounts)
        .saveAndFlush(any(SocialAccount.class));
    var result = login("kakao");
    assertFailure(result);
    verify(socialAccounts).saveAndFlush(any(SocialAccount.class));
    assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE-PROVIDER-DATA");
    assertThat(users.count()).isZero();
    assertThat(socialAccounts.count()).isZero();
  }

  private MvcResult login(String registration) throws Exception {
    int tokenRequestsBefore = provider.tokenRequests.get();
    int userInfoRequestsBefore = provider.userInfoRequests.get();
    var start =
        mvc.perform(get("/oauth2/authorization/" + registration))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse();
    var query =
        UriComponentsBuilder.fromUriString(start.getRedirectedUrl()).build().getQueryParams();
    provider.nonce = query.getFirst("nonce");
    var result =
        mvc.perform(
                get("/login/oauth2/code/" + registration)
                    .cookie(start.getCookie("HANMAUM_SESSION"))
                    .param("code", "synthetic-code")
                    .param(
                        "state", UriUtils.decode(query.getFirst("state"), StandardCharsets.UTF_8)))
            .andReturn();
    assertThat(provider.tokenRequests.get()).isEqualTo(tokenRequestsBefore + 1);
    assertThat(provider.userInfoRequests.get()).isEqualTo(userInfoRequestsBefore + 1);
    return result;
  }

  private void assertRedirect(MvcResult result) throws Exception {
    status().is3xxRedirection().match(result);
    redirectedUrl("/").match(result);
  }

  private void assertFailure(MvcResult result) throws Exception {
    status().isUnauthorized().match(result);
    jsonPath("$.success").value(false).match(result);
    jsonPath("$.code").value("OAUTH_LOGIN_FAILED").match(result);
    jsonPath("$.message").value("소셜 로그인에 실패했습니다.").match(result);
    Cookie cookie = result.getResponse().getCookie("HANMAUM_SESSION");
    var probe = get("/api/test/login-probe");
    if (cookie != null) probe.cookie(cookie);
    mvc.perform(probe).andExpect(status().isUnauthorized());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'",
                Long.class))
        .isZero();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProviderConfig {
    @Bean(destroyMethod = "close")
    FakeProvider fakeProvider(ObjectMapper mapper) throws IOException, JOSEException {
      return new FakeProvider(mapper);
    }

    @Bean
    ClientRegistrationRepository testClients(FakeProvider provider) {
      return new InMemoryClientRegistrationRepository(
          registration(provider, "google")
              .scope("openid", "profile", "email")
              .jwkSetUri(provider.baseUrl() + "/jwks")
              .issuerUri(provider.baseUrl())
              .userNameAttributeName("sub")
              .build(),
          registration(provider, "kakao")
              .scope("profile_nickname")
              .userNameAttributeName("id")
              .build());
    }

    private ClientRegistration.Builder registration(FakeProvider provider, String name) {
      return ClientRegistration.withRegistrationId(name)
          .clientId("test-client")
          .clientSecret("test-secret")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .authorizationUri(provider.baseUrl() + "/authorize")
          .tokenUri(provider.baseUrl() + "/" + name + "/token")
          .userInfoUri(provider.baseUrl() + "/" + name + "/userinfo");
    }

    @Bean
    LoginProbe loginProbe() {
      return new LoginProbe();
    }
  }

  @RestController
  static class LoginProbe {
    @GetMapping("/api/test/login-probe")
    String probe() {
      return "authenticated";
    }
  }

  // Local synthetic provider: no Google/Kakao requests or development credentials are used.
  static class FakeProvider implements AutoCloseable {
    private final HttpServer server;
    private final ObjectMapper mapper;
    private final RSAKey signingKey;
    final AtomicInteger tokenRequests = new AtomicInteger();
    final AtomicInteger userInfoRequests = new AtomicInteger();
    volatile String name;
    volatile Object kakaoId;
    volatile String googleUserInfoSubject;
    volatile String nonce;
    volatile boolean userInfoFails;

    FakeProvider(ObjectMapper mapper) throws IOException, JOSEException {
      this.mapper = mapper;
      signingKey = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::respond);
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/token")) tokenRequests.incrementAndGet();
      if (path.endsWith("/userinfo")) userInfoRequests.incrementAndGet();
      Object payload;
      int statusCode = 200;
      try {
        if (path.equals("/jwks")) {
          payload = new JWKSet(signingKey.toPublicJWK()).toJSONObject();
        } else if (path.endsWith("/token")) {
          if (path.startsWith("/google")) {
            payload =
                Map.of(
                    "access_token",
                    "test-access-token",
                    "token_type",
                    "Bearer",
                    "expires_in",
                    300,
                    "scope",
                    "openid profile email",
                    "id_token",
                    idToken());
          } else {
            payload =
                Map.of(
                    "access_token",
                    "test-access-token",
                    "token_type",
                    "Bearer",
                    "expires_in",
                    300,
                    "scope",
                    "profile_nickname");
          }
        } else if (path.endsWith("/userinfo") && userInfoFails) {
          statusCode = 500;
          payload = Map.of("error", "synthetic provider failure");
        } else if (path.equals("/google/userinfo")) {
          var claims = new java.util.HashMap<String, Object>();
          claims.put("sub", googleUserInfoSubject);
          claims.put("email", "same@example.invalid");
          if (name != null) claims.put("name", name);
          payload = claims;
        } else if (path.equals("/kakao/userinfo")) {
          payload =
              Map.of(
                  "id",
                  kakaoId,
                  "kakao_account",
                  Map.of(
                      "email",
                      "same@example.invalid",
                      "profile",
                      name == null ? Map.of() : Map.of("nickname", name)));
        } else {
          statusCode = 404;
          payload = Map.of("error", "unknown endpoint");
        }
      } catch (JOSEException exception) {
        throw new IOException("Synthetic token signing failed", exception);
      }
      byte[] body = mapper.writeValueAsBytes(payload);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
      exchange.close();
    }

    private String idToken() throws JOSEException {
      Instant now = Instant.now();
      var token =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(signingKey.getKeyID())
                  .type(JOSEObjectType.JWT)
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer(baseUrl())
                  .audience("test-client")
                  .subject("42")
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plusSeconds(300)))
                  .claim("nonce", nonce)
                  .build());
      token.sign(new RSASSASigner(signingKey));
      return token.serialize();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
