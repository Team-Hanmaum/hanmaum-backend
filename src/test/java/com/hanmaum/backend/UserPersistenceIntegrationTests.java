package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanmaum.backend.user.entity.AppUser;
import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.AppUserRepository;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UserPersistenceIntegrationTests {
  @Autowired AppUserRepository users;
  @Autowired SocialAccountRepository socialAccounts;
  @Autowired EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;

  @Test
  void persistsProviderIdentityAndServerGeneratedIdsAndTimestamps() {
    AppUser user = users.saveAndFlush(new AppUser("수진"));
    SocialAccount account =
        socialAccounts.saveAndFlush(
            new SocialAccount(user, SocialProvider.GOOGLE, "google-test-1"));
    UUID userId = user.getId();
    UUID accountId = account.getId();
    entityManager.clear();

    SocialAccount restored =
        socialAccounts
            .findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-test-1")
            .orElseThrow();
    assertThat(restored.getId()).isEqualTo(accountId).isNotNull();
    assertThat(restored.getProvider()).isEqualTo(SocialProvider.GOOGLE);
    assertThat(restored.getProviderUserId()).isEqualTo("google-test-1");
    assertThat(restored.getCreatedAt()).isNotNull();
    assertThat(restored.getUser().getId()).isEqualTo(userId).isNotNull();
    assertThat(restored.getUser().getDisplayName()).isEqualTo("수진");
    assertThat(restored.getUser().getCreatedAt()).isNotNull();
    assertThat(restored.getUser().getUpdatedAt())
        .isAfterOrEqualTo(restored.getUser().getCreatedAt());
    assertThat(
            socialAccounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "google-test-1"))
        .isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "이름")
  void doesNotInventRequiredNameOrVarcharLengthPolicy(String name) {
    String displayName = name == null ? null : name.repeat(300);
    AppUser user = users.saveAndFlush(new AppUser(displayName));
    UUID userId = user.getId();
    entityManager.clear();

    assertThat(users.findById(userId).orElseThrow().getDisplayName()).isEqualTo(displayName);
  }

  @Test
  void preventsTheSameProviderIdentityFromBelongingToTwoUsers() {
    AppUser first = users.saveAndFlush(new AppUser("첫 번째 사용자"));
    AppUser second = users.saveAndFlush(new AppUser("두 번째 사용자"));
    socialAccounts.saveAndFlush(new SocialAccount(first, SocialProvider.GOOGLE, "duplicate-id"));

    assertThatThrownBy(
            () ->
                socialAccounts.saveAndFlush(
                    new SocialAccount(second, SocialProvider.GOOGLE, "duplicate-id")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("social_account_provider_user_id_uq");
  }

  @Test
  void keepsTheSameIdentifierFromDifferentProvidersSeparate() {
    AppUser googleUser = users.saveAndFlush(new AppUser("같은 표시 이름"));
    AppUser kakaoUser = users.saveAndFlush(new AppUser("같은 표시 이름"));
    socialAccounts.saveAndFlush(new SocialAccount(googleUser, SocialProvider.GOOGLE, "same-id"));
    socialAccounts.saveAndFlush(new SocialAccount(kakaoUser, SocialProvider.KAKAO, "same-id"));
    entityManager.clear();

    assertThat(
            socialAccounts
                .findByProviderAndProviderUserId(SocialProvider.GOOGLE, "same-id")
                .orElseThrow()
                .getUser()
                .getId())
        .isEqualTo(googleUser.getId());
    assertThat(
            socialAccounts
                .findByProviderAndProviderUserId(SocialProvider.KAKAO, "same-id")
                .orElseThrow()
                .getUser()
                .getId())
        .isEqualTo(kakaoUser.getId())
        .isNotEqualTo(googleUser.getId());
  }

  @Test
  void retrievesOnlyTheRequestedUsersSocialAccounts() {
    AppUser first = users.saveAndFlush(new AppUser("첫 번째 사용자"));
    AppUser second = users.saveAndFlush(new AppUser("두 번째 사용자"));
    socialAccounts.saveAndFlush(new SocialAccount(first, SocialProvider.GOOGLE, "google-1"));
    socialAccounts.saveAndFlush(new SocialAccount(first, SocialProvider.KAKAO, "kakao-1"));
    socialAccounts.saveAndFlush(new SocialAccount(second, SocialProvider.KAKAO, "kakao-2"));
    entityManager.clear();

    assertThat(socialAccounts.findAllByUser_Id(first.getId()))
        .extracting(SocialAccount::getProvider)
        .containsExactlyInAnyOrder(SocialProvider.GOOGLE, SocialProvider.KAKAO);
    assertThat(socialAccounts.findAllByUser_Id(second.getId()))
        .extracting(SocialAccount::getProviderUserId)
        .containsExactly("kakao-2");
  }

  @Test
  void rejectsASocialAccountWithoutAnExistingUser() {
    assertThatThrownBy(() -> insertSocialAccount(UUID.randomUUID(), "GOOGLE", "orphan-id"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("social_account_user_fk");
  }

  @Test
  void rejectsUnsupportedProvidersAtTheDatabaseBoundary() {
    AppUser user = users.saveAndFlush(new AppUser(null));

    assertThatThrownBy(() -> insertSocialAccount(user.getId(), "UNKNOWN", "unsupported-id"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("social_account_provider_ck");
  }

  @Test
  void deletingAUserCascadesOnlyToThatUsersSocialAccounts() {
    AppUser removed = users.saveAndFlush(new AppUser("삭제 대상"));
    AppUser retained = users.saveAndFlush(new AppUser("유지 대상"));
    socialAccounts.saveAndFlush(
        new SocialAccount(removed, SocialProvider.GOOGLE, "removed-google"));
    socialAccounts.saveAndFlush(new SocialAccount(removed, SocialProvider.KAKAO, "removed-kakao"));
    socialAccounts.saveAndFlush(
        new SocialAccount(retained, SocialProvider.KAKAO, "retained-kakao"));

    jdbc.update("DELETE FROM app_user WHERE id = ?", removed.getId());
    entityManager.clear();

    assertThat(users.findById(removed.getId())).isEmpty();
    assertThat(socialAccounts.findAllByUser_Id(removed.getId())).isEmpty();
    assertThat(users.findById(retained.getId())).isPresent();
    assertThat(socialAccounts.findAllByUser_Id(retained.getId()))
        .extracting(SocialAccount::getProviderUserId)
        .containsExactly("retained-kakao");
  }

  private void insertSocialAccount(UUID userId, String provider, String providerUserId) {
    jdbc.update(
        """
        INSERT INTO social_account (id, user_id, provider, provider_user_id, created_at)
        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        userId,
        provider,
        providerUserId);
  }
}
