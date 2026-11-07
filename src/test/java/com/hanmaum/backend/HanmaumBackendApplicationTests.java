package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class HanmaumBackendApplicationTests {

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired SessionRepository<?> sessions;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void appliesAndValidatesMigrationsOnPostgresql() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("17");
    flyway.validate();
    assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class))
        .contains("PostgreSQL 17.11");
  }

  @Test
  void persistsAndDeletesSessionsInPostgresql() {
    assertSessionRoundTrip(sessions);
  }

  @Test
  void provenanceDecisionIndexesSupportBothItemVersionLookups() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              // Check index eligibility independent of the planner's legitimate small-table seq
              // scans.
              jdbcTemplate.execute("SET LOCAL enable_seqscan=off");
              var plan =
                  jdbcTemplate.queryForObject(
                      "EXPLAIN (FORMAT JSON,COSTS OFF) SELECT proposal_id FROM proposal_decision WHERE space_id=? AND item_id=? AND item_version=1",
                      String.class,
                      UUID.randomUUID(),
                      UUID.randomUUID());
              assertThat(plan).contains("proposal_decision_item_version_ix");
              var requests =
                  jdbcTemplate.queryForObject(
                      "EXPLAIN (FORMAT JSON,COSTS OFF) SELECT proposal_id FROM change_request_entry WHERE space_id=? AND result_item_id=? AND result_item_version=1 AND status='APPLIED' AND proposal_id IS NOT NULL",
                      String.class,
                      UUID.randomUUID(),
                      UUID.randomUUID());
              assertThat(requests).contains("change_request_applied_item_version_ix");
            });
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
