package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.AppUserRepository;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import com.hanmaum.backend.user.service.SocialLoginMemberService;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SocialLoginMemberIntegrationTests {
  @Autowired SocialLoginMemberService members;
  @Autowired AppUserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoSpyBean SocialAccountRepository socialAccounts;

  @BeforeEach
  void clearIsolatedDatabase() {
    jdbc.update("DELETE FROM app_user");
  }

  @Test
  void reusesTheMemberWithoutChangingTheOriginalNameOrProfileTimestamp() {
    UUID firstId = members.findOrCreate(SocialProvider.GOOGLE, "same-user", "최초 이름");
    var before = users.findById(firstId).orElseThrow();

    UUID nextId = members.findOrCreate(SocialProvider.GOOGLE, "same-user", "변경된 제공자 이름");
    var after = users.findById(nextId).orElseThrow();

    assertThat(nextId).isEqualTo(firstId);
    assertThat(after.getDisplayName()).isEqualTo("최초 이름");
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(users.count()).isEqualTo(1);
    assertThat(socialAccounts.count()).isEqualTo(1);
  }

  @Test
  void preservesAMissingInitialNameOnLaterLogins() {
    UUID id = members.findOrCreate(SocialProvider.KAKAO, "42", null);
    assertThat(members.findOrCreate(SocialProvider.KAKAO, "42", "나중 이름")).isEqualTo(id);
    assertThat(users.findById(id).orElseThrow().getDisplayName()).isNull();
  }

  @Test
  void doesNotMergeIdenticalIdsOrNamesAcrossProviders() {
    UUID google = members.findOrCreate(SocialProvider.GOOGLE, "42", "같은 이름");
    UUID kakao = members.findOrCreate(SocialProvider.KAKAO, "42", "같은 이름");
    assertThat(google).isNotEqualTo(kakao);
    assertThat(users.count()).isEqualTo(2);
    assertThat(socialAccounts.count()).isEqualTo(2);
  }

  @Test
  void concurrentFirstLoginsReturnOneMemberWithoutOrphanUsers() throws Exception {
    int callers = 6;
    var ready = new CountDownLatch(callers);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(callers)) {
      var futures = new ArrayList<Future<UUID>>();
      for (int i = 0; i < callers; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("timeout");
                  return members.findOrCreate(SocialProvider.GOOGLE, "concurrent-user", "최초 이름");
                }));
      }
      try {
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      var ids = new ArrayList<UUID>();
      for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
      assertThat(ids.stream().distinct()).hasSize(1);
    }
    assertThat(users.count()).isEqualTo(1);
    assertThat(socialAccounts.count()).isEqualTo(1);
  }

  @Test
  void rollsBackTheUserAndReleasesTheLockWhenSocialAccountSavingFails() {
    doThrow(new DataAccessResourceFailureException("synthetic database failure"))
        .when(socialAccounts)
        .saveAndFlush(any(SocialAccount.class));

    assertThatThrownBy(() -> members.findOrCreate(SocialProvider.KAKAO, "42", "최초 이름"))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(users.count()).isZero();
    assertThat(socialAccounts.count()).isZero();

    reset(socialAccounts);
    assertThat(members.findOrCreate(SocialProvider.KAKAO, "42", "다시 시도")).isNotNull();
    assertThat(users.count()).isEqualTo(1);
    assertThat(socialAccounts.count()).isEqualTo(1);
  }
}
