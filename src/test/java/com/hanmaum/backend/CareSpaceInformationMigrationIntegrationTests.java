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
class CareSpaceInformationMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void upgradesV19RequestsAndDeletionResultsWithoutRewritingDocumentsOrChecksums() {
    String schema = "space_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var previous =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("19")
            .load();
    previous.migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var checksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM "
                + schema
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    UUID owner = UUID.randomUUID(),
        member = UUID.randomUUID(),
        ownerMembership = UUID.randomUUID(),
        memberMembership = UUID.randomUUID(),
        space = UUID.randomUUID(),
        item = UUID.randomUUID(),
        document = UUID.randomUUID(),
        deleteDocument = UUID.randomUUID(),
        createDocument = UUID.randomUUID(),
        request = UUID.randomUUID(),
        batch = UUID.randomUUID(),
        deletedEntry = UUID.randomUUID();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SET LOCAL search_path TO " + schema);
              for (UUID actor : new UUID[] {owner, member})
                jdbc.update(
                    "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,'기존 합성 가족',now(),now())",
                    actor);
              jdbc.update(
                  "INSERT INTO care_space(id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'기존 공간',now(),now())",
                  space,
                  ownerMembership,
                  owner);
              jdbc.update(
                  "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now()),(?,?,?,now())",
                  ownerMembership,
                  space,
                  owner,
                  memberMembership,
                  space,
                  member);
              jdbc.update(
                  "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT','{\"title\":\"보존할 항목\",\"fields\":{}}',now()),(?,?,'DIRECT_INPUT','{\"values\":null}',now()),(?,?,'DIRECT_INPUT','{\"values\":{\"itemType\":\"SCHEDULE\",\"title\":\"기존 요청\",\"fields\":{}}}',now())",
                  document,
                  space,
                  deleteDocument,
                  space,
                  createDocument,
                  space);
              jdbc.update(
                  "INSERT INTO care_item(id,space_id,item_type,version,current_document_id,status,visibility,created_by_membership_id,created_at,updated_at) VALUES (?,?,'SCHEDULE',2,?,'SCHEDULED','DELETED',?,now(),now())",
                  item,
                  space,
                  document,
                  ownerMembership);
              jdbc.update(
                  "INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at) VALUES (?,?,1,'CREATE',?,'SCHEDULED',?,now()),(?,?,2,'DELETE',?,'SCHEDULED',?,now())",
                  space,
                  item,
                  document,
                  ownerMembership,
                  space,
                  item,
                  document,
                  ownerMembership);
              jdbc.update(
                  "INSERT INTO change_request(id,space_id,submitted_by_membership_id,submitted_at) VALUES (?,?,?,now())",
                  request,
                  space,
                  memberMembership);
              jdbc.update(
                  "INSERT INTO change_request_decision_batch(id,space_id,decided_by_membership_id,decision,decided_at) VALUES (?,?,?,'APPLIED',now())",
                  batch,
                  space,
                  ownerMembership);
              jdbc.update(
                  "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,target_item_id,expected_item_version,request_document_id,status,decision_batch_id,decision_ordinal_no,result_item_id,result_item_version,result_document_id) VALUES (?,?,?,0,'DIRECT_INPUT','DELETE_ITEM',?,1,?,'APPLIED',?,0,?,2,?)",
                  deletedEntry,
                  space,
                  request,
                  item,
                  deleteDocument,
                  batch,
                  item,
                  document);
              jdbc.update(
                  "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,request_document_id) VALUES (?,?,?,1,'DIRECT_INPUT','CREATE_ITEM',?)",
                  UUID.randomUUID(),
                  space,
                  request,
                  createDocument);
            });
    String entryColumns =
        "id,action,status,target_item_id,expected_item_version,result_item_id,result_item_version,result_document_id,request_document_id";
    var original =
        jdbc.queryForList(
            "SELECT "
                + entryColumns
                + " FROM "
                + schema
                + ".change_request_entry ORDER BY ordinal_no");
    var upgraded =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("20")
            .load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("20");
    assertThat(
            jdbc.queryForList(
                "SELECT "
                    + entryColumns
                    + " FROM "
                    + schema
                    + ".change_request_entry ORDER BY ordinal_no"))
        .isEqualTo(original);
    assertThat(
            jdbc.queryForList(
                "SELECT version,checksum FROM "
                    + schema
                    + ".flyway_schema_history WHERE version::int<=19 ORDER BY installed_rank"))
        .isEqualTo(checksums);
    assertThat(
            jdbc.queryForObject(
                "SELECT body->>'title' FROM " + schema + ".content_document WHERE id=?",
                String.class,
                document))
        .isEqualTo("보존할 항목");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".change_request_entry WHERE expected_space_version IS NOT NULL OR result_space_version IS NOT NULL",
                Integer.class))
        .isZero();
    UUID entry = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,expected_space_version,request_document_id) VALUES (?,?,?,2,'DIRECT_INPUT','UPDATE_SPACE',1,?)",
        entry,
        space,
        request,
        createDocument);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".change_request_entry SET target_item_id=?,expected_item_version=1 WHERE id=?",
                    item,
                    entry))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".change_request_entry SET expected_space_version=NULL WHERE id=?",
                    entry))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".change_request_entry SET result_space_version=1 WHERE id=?",
                    deletedEntry))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
