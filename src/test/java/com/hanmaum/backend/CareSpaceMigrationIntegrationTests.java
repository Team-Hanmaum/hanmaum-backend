package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareSpaceMigrationIntegrationTests {
  @Autowired DataSource dataSource;

  @Test
  void upgradesExistingV2DataWithoutTouchingUsersOrSessions() {
    // A dedicated schema in Testcontainers, never the running development database.
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target("2")
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(dataSource);
    UUID user = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".app_user (id, display_name, created_at, updated_at) VALUES (?, '기존 회원', now(), now())",
        user);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".social_account (id, user_id, provider, provider_user_id, created_at) VALUES (?, ?, 'GOOGLE', 'existing', now())",
        UUID.randomUUID(),
        user);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, 1, 1, 100, 101, ?)",
        session.toString(),
        UUID.randomUUID().toString(),
        user.toString());
    var upgraded =
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM " + schema + ".app_user WHERE id = ?",
                String.class,
                user))
        .isEqualTo("기존 회원");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".social_account WHERE user_id = ?",
                Integer.class,
                user))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT principal_name FROM " + schema + ".spring_session WHERE primary_id = ?",
                String.class,
                session.toString()))
        .isEqualTo(user.toString());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".care_space", Integer.class))
        .isZero();
  }
}
