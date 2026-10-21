package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class CareItemMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;

  @ParameterizedTest
  @ValueSource(strings = {"6", "7"})
  void upgradesThroughV10WithoutLosingDataOrInventingEvidenceSnapshots(String startingVersion) {
    String schema = "care_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target(startingVersion)
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var originalChecksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM "
                + schema
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    var tx = new TransactionTemplate(transactions);
    UUID user = UUID.randomUUID(),
        space = UUID.randomUUID(),
        member = UUID.randomUUID(),
        document = UUID.randomUUID();
    tx.executeWithoutResult(
        status -> {
          jdbc.execute("SET LOCAL search_path TO " + schema);
          jdbc.update(
              "INSERT INTO app_user (id,display_name,created_at,updated_at) VALUES (?,'기존',now(),now())",
              user);
          jdbc.update(
              "INSERT INTO care_space (id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'기존 공간',now(),now())",
              space,
              member,
              user);
          jdbc.update(
              "INSERT INTO space_membership (id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
              member,
              space,
              user);
          jdbc.update(
              "INSERT INTO content_document (id,space_id,kind,body,created_at) VALUES (?,?,'AI_OUTPUT','{\"title\":\"기존 제안\"}',now())",
              document,
              space);
        });
    var flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("8")
            .load();
    flyway.migrate();
    flyway.validate();
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                schema + ".ai_proposal_space_page_ix"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM " + schema + ".content_document WHERE id=?",
                String.class,
                document))
        .contains("기존 제안");
    UUID item = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".care_item (id,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at) VALUES (?,?,'SCHEDULE',1,?,'SCHEDULED',?,now(),now())",
        item,
        space,
        document,
        member);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".care_item SET status='NOT_STARTED' WHERE id=?", item))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE " + schema + ".care_item SET version=0 WHERE id=?", item))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".care_item_basis (id,space_id,item_id,created_at) VALUES (?,?,?,now())",
                    UUID.randomUUID(),
                    space,
                    item))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".care_item_basis (id,space_id,item_id,direct_document_id,created_at) VALUES (?,?,?,?,now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    item,
                    document))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".content_document (id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT','{}',now())",
        UUID.randomUUID(),
        space);

    UUID source = UUID.randomUUID(), run = UUID.randomUUID();
    tx.executeWithoutResult(
        status -> {
          jdbc.execute("SET LOCAL search_path TO " + schema);
          jdbc.update(
              "INSERT INTO source_record (id,space_id,author_membership_id,body,recorded_at) VALUES (?,?,?,'기존 원본',now())",
              source,
              space,
              member);
          jdbc.update(
              """
          INSERT INTO analysis_run (id,space_id,record_id,record_version,attempt_no,request_id,
            input_recorded_at,timezone,requested_by_membership_id,status,created_at,started_at,worker_token,lease_until)
          VALUES (?,?,?,1,1,?,now(),'Asia/Seoul',?,'RUNNING',now(),now(),?,now()+interval '2 minutes')
          """,
              run,
              space,
              source,
              UUID.randomUUID(),
              member,
              UUID.randomUUID());
          jdbc.update("INSERT INTO analysis_input_source VALUES (?,?,?,1)", space, run, source);
          jdbc.update(
              "INSERT INTO analysis_input_item (space_id,run_id,item_id,item_version,document_id) VALUES (?,?,?,1,?)",
              space,
              run,
              item,
              document);
        });
    var oldRun = jdbc.queryForMap("SELECT * FROM " + schema + ".analysis_run WHERE id=?", run);
    var oldItem = jdbc.queryForMap("SELECT * FROM " + schema + ".care_item WHERE id=?", item);
    var v8Checksum =
        jdbc.queryForObject(
            "SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version='8'",
            Integer.class);
    var upgraded =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("9")
            .load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("9");
    assertThat(
            jdbc.queryForList(
                "SELECT version,checksum FROM "
                    + schema
                    + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
        .containsAll(originalChecksums);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class))
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    assertThat(jdbc.queryForMap("SELECT * FROM " + schema + ".analysis_run WHERE id=?", run))
        .isEqualTo(oldRun);
    assertThat(jdbc.queryForMap("SELECT * FROM " + schema + ".care_item WHERE id=?", item))
        .isEqualTo(oldItem);
    assertThat(
            jdbc.queryForObject(
                "SELECT source_evidence_ids IS NULL FROM "
                    + schema
                    + ".analysis_input_item WHERE run_id=?",
                Boolean.class,
                run))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM " + schema + ".content_document WHERE id=?",
                String.class,
                document))
        .contains("기존 제안");
    assertThat(
            jdbc.queryForObject(
                "SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version='8'",
                Integer.class))
        .isEqualTo(v8Checksum);
    var v10 =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("10")
            .load();
    v10.migrate();
    v10.validate();
    assertThat(v10.info().current().getVersion().getVersion()).isEqualTo("10");
    var migratedRun = jdbc.queryForMap("SELECT * FROM " + schema + ".analysis_run WHERE id=?", run);
    assertThat(migratedRun).containsAllEntriesOf(oldRun);
    assertThat(migratedRun.get("contract_version")).isEqualTo("v1");
    assertThat(migratedRun.get("input_captured")).isEqualTo(false);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".analysis_input_basis", Integer.class))
        .isZero();
    assertThat(jdbc.queryForMap("SELECT * FROM " + schema + ".care_item WHERE id=?", item))
        .isEqualTo(oldItem);
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM " + schema + ".content_document WHERE id=?",
                String.class,
                document))
        .contains("기존 제안");
    assertThat(
            jdbc.queryForList(
                "SELECT version,checksum FROM "
                    + schema
                    + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
        .containsAll(originalChecksums);
  }
}
