package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AnalysisMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void v5UpgradeKeepsOriginalsAndDoesNotBackfillAnalysisAndEnforcesExecutionConstraints() {
    String schema = "analysis_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target("5")
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var transaction = new TransactionTemplate(transactionManager);
    UUID user = UUID.randomUUID(),
        space = UUID.randomUUID(),
        membership = UUID.randomUUID(),
        record = UUID.randomUUID();
    transaction.executeWithoutResult(
        tx -> {
          jdbc.execute("SET LOCAL search_path TO " + schema);
          jdbc.update(
              "INSERT INTO app_user (id, display_name, created_at, updated_at) VALUES (?, '기존 회원', now(), now())",
              user);
          jdbc.update(
              "INSERT INTO care_space (id, owner_membership_id, owner_user_id, subject_label, created_at, updated_at) VALUES (?, ?, ?, '엄마', now(), now())",
              space,
              membership,
              user);
          jdbc.update(
              "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now())",
              membership,
              space,
              user);
          jdbc.update(
              "INSERT INTO source_record (id, space_id, author_membership_id, body, recorded_at) VALUES (?, ?, ?, '기존 원문', now())",
              record,
              space,
              membership);
        });
    var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("6")
            .load();
    flyway.migrate();
    flyway.validate();
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM " + schema + ".source_record WHERE id = ?", String.class, record))
        .isEqualTo("기존 원문");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM " + schema + ".analysis_run", Integer.class))
        .isZero();
    String insert =
        "INSERT INTO "
            + schema
            + ".analysis_run (id, space_id, record_id, record_version, attempt_no, request_id, input_recorded_at, timezone, requested_by_membership_id, status, created_at) VALUES (?, ?, ?, 1, ?, ?, now(), 'Asia/Seoul', ?, 'QUEUED', now())";
    UUID run = UUID.randomUUID();
    jdbc.update(insert, run, space, record, 1, UUID.randomUUID(), membership);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    insert, UUID.randomUUID(), space, record, 2, UUID.randomUUID(), membership))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String update :
        new String[] {
          "status = 'RUNNING'",
          "status = 'FAILED', finished_at = now()",
          "worker_token = '" + UUID.randomUUID() + "'",
          "timezone = 'UTC'"
        }) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE " + schema + ".analysis_run SET " + update + " WHERE id = ?", run))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    insert,
                    UUID.randomUUID(),
                    space,
                    UUID.randomUUID(),
                    1,
                    UUID.randomUUID(),
                    membership))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
