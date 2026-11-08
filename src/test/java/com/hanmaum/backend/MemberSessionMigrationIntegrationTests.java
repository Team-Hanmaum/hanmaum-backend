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

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MemberSessionMigrationIntegrationTests {
  @Autowired DataSource dataSource;

  /** V22 retains valid/anonymous sessions, purges orphan identities, and rejects stale saves. */
  @Test
  void upgradeFromV21PreservesValidSessionsAndAddsAccountCascadeWithoutChangingChecksums() {
    String schema = "withdrawal_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var previous =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("21")
            .load();
    previous.migrate();
    var jdbc = new JdbcTemplate(dataSource);
    String prefix = schema + ".";
    var checksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM "
                + prefix
                + "flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
    UUID user = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + prefix
            + "app_user(id,display_name,created_at,updated_at) VALUES (?,'보존할 합성 회원',now(),now())",
        user);
    String valid = session(jdbc, prefix, user.toString());
    String orphan = session(jdbc, prefix, UUID.randomUUID().toString());
    String anonymous = session(jdbc, prefix, null);
    String legacy = session(jdbc, prefix, "legacy-non-member");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target("22")
        .load()
        .migrate();
    assertThat(
            jdbc.queryForList(
                "SELECT version,checksum FROM "
                    + prefix
                    + "flyway_schema_history WHERE version::int<=21 ORDER BY installed_rank"))
        .isEqualTo(checksums);
    assertThat(
            jdbc.queryForList("SELECT primary_id FROM " + prefix + "spring_session", String.class))
        .containsExactlyInAnyOrder(valid, anonymous, legacy);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + prefix
                    + "spring_session_attributes WHERE session_primary_id=?",
                Long.class,
                orphan))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT principal_user_id FROM " + prefix + "spring_session WHERE primary_id=?",
                UUID.class,
                valid))
        .isEqualTo(user);
    jdbc.update("DELETE FROM " + prefix + "app_user WHERE id=?", user);
    assertThat(
            jdbc.queryForList("SELECT primary_id FROM " + prefix + "spring_session", String.class))
        .containsExactlyInAnyOrder(anonymous, legacy);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + prefix
                    + "spring_session_attributes WHERE session_primary_id=?",
                Long.class,
                valid))
        .isZero();
    assertThatThrownBy(() -> session(jdbc, prefix, user.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private String session(JdbcTemplate jdbc, String prefix, String principal) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO "
            + prefix
            + "spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES (?,?,0,0,1800,9999999999999,?)",
        id,
        UUID.randomUUID().toString(),
        principal);
    jdbc.update(
        "INSERT INTO "
            + prefix
            + "spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES (?,'synthetic-state',?)",
        id,
        new byte[] {1, 2, 3});
    return id;
  }
}
