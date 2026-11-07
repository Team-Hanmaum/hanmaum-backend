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
class ShareLinkMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void upgradeFromV20PreservesExistingRowsChecksumsAndRequiresTargetsWithinTheSameSpace() {
    String schema = "sharing_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var previous =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("20")
            .load();
    previous.migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var checksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM "
                + schema
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    UUID user = UUID.randomUUID(),
        membership = UUID.randomUUID(),
        space = UUID.randomUUID(),
        document = UUID.randomUUID(),
        item = UUID.randomUUID();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SET LOCAL search_path TO " + schema);
              jdbc.update(
                  "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,'기존 합성 가족',now(),now())",
                  user);
              jdbc.update(
                  "INSERT INTO care_space(id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'보존할 공간',now(),now())",
                  space,
                  membership,
                  user);
              jdbc.update(
                  "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
                  membership,
                  space,
                  user);
              jdbc.update(
                  "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT','{\"title\":\"보존할 제목\",\"fields\":{}}',now())",
                  document,
                  space);
              jdbc.update(
                  "INSERT INTO care_item(id,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at) VALUES (?,?,'SCHEDULE',1,?,'SCHEDULED',?,now(),now())",
                  item,
                  space,
                  document,
                  membership);
            });
    var before = jdbc.queryForList("SELECT * FROM " + schema + ".care_item");
    var docs = jdbc.queryForList("SELECT * FROM " + schema + ".content_document");
    var upgraded =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("21")
            .load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("21");
    assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".care_item")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".content_document")).isEqualTo(docs);
    assertThat(
            jdbc.queryForList(
                "SELECT version,checksum FROM "
                    + schema
                    + ".flyway_schema_history WHERE version::int<=20 ORDER BY installed_rank"))
        .isEqualTo(checksums);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SET LOCAL search_path TO " + schema);
              UUID link = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO share_link(id,space_id,issued_by_membership_id,token_hash,url_prefix,created_at) VALUES (?,?,?,?,'https://example.com/share/',now())",
                  link,
                  space,
                  membership,
                  "a".repeat(64));
              jdbc.update(
                  "INSERT INTO share_link_target(space_id,share_link_id,ordinal_no,item_id,item_type,shared_item_version,includes_change) VALUES (?,?,0,?,'SCHEDULE',1,false)",
                  space,
                  link,
                  item);
            });
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='share_link'",
                String.class,
                schema))
        .containsExactlyInAnyOrder(
            "id", "space_id", "issued_by_membership_id", "token_hash", "url_prefix", "created_at");
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name='share_link_target'",
                String.class,
                schema))
        .containsExactlyInAnyOrder(
            "space_id",
            "share_link_id",
            "ordinal_no",
            "item_id",
            "item_type",
            "shared_item_version",
            "includes_change");
    UUID otherSpace = UUID.randomUUID(), otherMembership = UUID.randomUUID();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SET LOCAL search_path TO " + schema);
              jdbc.update(
                  "INSERT INTO care_space(id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'별도 공간',now(),now())",
                  otherSpace,
                  otherMembership,
                  user);
              jdbc.update(
                  "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
                  otherMembership,
                  otherSpace,
                  user);
            });
    // Both identifiers exist, but belong to different spaces: composite foreign keys must reject
    // it.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".share_link SET issued_by_membership_id=? WHERE space_id=?",
                    otherMembership,
                    space))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".share_link_target SET space_id=? WHERE item_id=?",
                    otherSpace,
                    item))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
