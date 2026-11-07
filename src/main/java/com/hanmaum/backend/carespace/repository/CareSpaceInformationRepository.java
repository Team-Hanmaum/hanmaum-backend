package com.hanmaum.backend.carespace.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Caller holds the space row lock for every information mutation. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareSpaceInformationRepository {
  private final JdbcTemplate jdbc;

  public CareSpaceInformationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public State current(UUID space) {
    return jdbc.queryForObject(
        "SELECT subject_label,version,updated_at,owner_user_id FROM care_space WHERE id=? AND lifecycle='ACTIVE'",
        (rs, n) ->
            new State(
                rs.getString(1),
                rs.getLong(2),
                rs.getTimestamp(3).toInstant(),
                rs.getObject(4, UUID.class)),
        space);
  }

  public boolean duplicate(UUID space, UUID owner, String label) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM care_space WHERE owner_user_id=? AND subject_label=? AND id<>?)",
            Boolean.class,
            owner,
            label,
            space));
  }

  public void update(UUID space, long expected, String label, Instant now) {
    if (jdbc.update(
            "UPDATE care_space SET subject_label=?,version=version+1,updated_at=? WHERE id=? AND lifecycle='ACTIVE' AND version=?",
            label,
            Timestamp.from(now),
            space,
            expected)
        != 1) throw new IllegalStateException("Locked space changed during information update");
  }

  public UUID document(UUID space, String body, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT',?::jsonb,?)",
        id,
        space,
        body,
        Timestamp.from(now));
    return id;
  }

  public Optional<String> label(UUID space, UUID document) {
    return jdbc
        .query(
            "SELECT body->>'subjectLabel' FROM content_document WHERE space_id=? AND id=? AND care_document_available(id)",
            (rs, n) -> rs.getString(1),
            space,
            document)
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  public record State(String label, long version, Instant updatedAt, UUID owner) {}
}
