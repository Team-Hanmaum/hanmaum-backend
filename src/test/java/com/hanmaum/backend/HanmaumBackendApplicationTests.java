package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class HanmaumBackendApplicationTests {

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired SessionRepository<?> sessions;

  @Test
  void appliesAndValidatesMigrationsOnPostgresql() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    flyway.validate();
    assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class))
        .contains("PostgreSQL 17.11");
  }

  @Test
  void persistsAndDeletesSessionsInPostgresql() {
    assertSessionRoundTrip(sessions);
  }

  private <S extends Session> void assertSessionRoundTrip(SessionRepository<S> repository) {
    S session = repository.createSession();
    session.setAttribute("test-member", "member-1");
    repository.save(session);

    S restored = repository.findById(session.getId());
    assertThat(restored).isNotNull();
    assertThat(restored.<String>getAttribute("test-member")).isEqualTo("member-1");

    repository.deleteById(session.getId());
    assertThat(repository.findById(session.getId())).isNull();
  }
}
