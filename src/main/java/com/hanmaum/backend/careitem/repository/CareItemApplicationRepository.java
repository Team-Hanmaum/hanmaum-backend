package com.hanmaum.backend.careitem.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CareItem write boundary. Caller validates the full batch before invoking any mutation. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareItemApplicationRepository {
  private final JdbcTemplate jdbc;

  public CareItemApplicationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Item> lock(UUID space, UUID id) {
    return jdbc
        .query(
            """
        SELECT i.*,d.body::text body,care_document_available(d.id) AS available
        FROM care_item i JOIN content_document d ON d.id=i.current_document_id
        WHERE i.space_id=? AND i.id=? FOR UPDATE OF i
        """,
            (rs, n) ->
                new Item(
                    id,
                    rs.getString("item_type"),
                    rs.getLong("version"),
                    rs.getObject("current_document_id", UUID.class),
                    rs.getString("status"),
                    rs.getString("body"),
                    "VISIBLE".equals(rs.getString("visibility"))
                        && !rs.getBoolean("needs_reconfirmation")
                        && rs.getBoolean("available")),
            space,
            id)
        .stream()
        .findFirst();
  }

  public boolean activeAssignee(UUID space, UUID member) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM space_membership WHERE space_id=? AND id=? AND is_active)",
            Boolean.class,
            space,
            member));
  }

  public UUID document(
      UUID space, String body, UUID previous, UUID proposalDocument, UUID run, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'CONFIRMED_ITEM',?::jsonb,?)",
        id,
        space,
        body,
        Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id)
        SELECT ?,?,parent FROM (
          SELECT CAST(? AS uuid) parent UNION SELECT CAST(? AS uuid)
          UNION SELECT document_id FROM analysis_input_item WHERE space_id=? AND run_id=?
          UNION SELECT direct_document_id FROM analysis_input_basis WHERE space_id=? AND run_id=?
        ) parents WHERE parent IS NOT NULL
        """,
        space,
        id,
        previous,
        proposalDocument,
        space,
        run,
        space,
        run);
    return id;
  }

  public void create(
      UUID space, UUID id, String type, String status, UUID document, UUID member, Instant now) {
    jdbc.update(
        """
        INSERT INTO care_item(id,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at)
        VALUES (?,?,?,1,?,?,?,?,?)
        """,
        id,
        space,
        type,
        document,
        status,
        member,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void update(
      UUID space, UUID id, long expected, UUID document, String status, Instant now) {
    int count =
        jdbc.update(
            """
        UPDATE care_item SET version=version+1,current_document_id=?,status=?,updated_at=?
        WHERE space_id=? AND id=? AND version=?
        """,
            document,
            status,
            Timestamp.from(now),
            space,
            id,
            expected);
    if (count != 1) throw new IllegalStateException("Application lost its locked item");
  }

  public void basis(UUID space, UUID item, UUID evidence, UUID member, Instant now) {
    jdbc.update(
        """
        INSERT INTO care_item_basis(id,space_id,item_id,source_evidence_id,submitted_by_membership_id,created_at)
        VALUES (?,?,?,?,?,?) ON CONFLICT (item_id,source_evidence_id) DO NOTHING
        """,
        UUID.randomUUID(),
        space,
        item,
        evidence,
        member,
        Timestamp.from(now));
  }

  public UUID directInput(UUID space, String body, UUID previous, Instant now) {
    UUID document = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT',?::jsonb,?)",
        document,
        space,
        body,
        Timestamp.from(now));
    if (previous != null)
      jdbc.update(
          "INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id) VALUES (?,?,?)",
          space,
          document,
          previous);
    return document;
  }

  public void directBasis(
      UUID space, UUID item, UUID document, UUID submitter, Instant submittedAt) {
    jdbc.update(
        "INSERT INTO care_item_basis(id,space_id,item_id,direct_document_id,submitted_by_membership_id,created_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(),
        space,
        item,
        document,
        submitter,
        Timestamp.from(submittedAt));
  }

  /** Immutable previous documents/assignments remain available to authorized history readers. */
  public void unassignOpenTasks(UUID space, UUID departing, UUID actor, Instant now) {
    jdbc.update(
        """
      WITH affected AS MATERIALIZED (
        SELECT i.id,i.version,i.status,d.id previous,gen_random_uuid() document,
               jsonb_set(d.body,'{fields,assigneeMembershipId}','null'::jsonb) body
        FROM care_item i JOIN content_document d ON d.space_id=i.space_id AND d.id=i.current_document_id
        WHERE i.space_id=? AND i.item_type='TASK' AND i.status IN ('NOT_STARTED','IN_PROGRESS')
          AND i.visibility='VISIBLE' AND lower(d.body #>> '{fields,assigneeMembershipId}')=?
          AND care_document_available(d.id) FOR UPDATE OF i
      ), docs AS (
        INSERT INTO content_document(id,space_id,kind,body,created_at)
        SELECT document,?,'CONFIRMED_ITEM',body,? FROM affected RETURNING id
      ), parents AS (
        INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id)
        SELECT ?,docs.id,a.previous FROM docs JOIN affected a ON a.document=docs.id
      ), updated AS (
        UPDATE care_item i SET current_document_id=docs.id,version=i.version+1,updated_at=?
        FROM docs JOIN affected a ON a.document=docs.id WHERE i.id=a.id
        RETURNING i.id,i.version,i.current_document_id,i.status
      ), released AS (
        DELETE FROM care_item_edit_lock l USING updated u WHERE l.item_id=u.id
      )
      INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at)
      SELECT ?,id,version,'UNASSIGN',current_document_id,status,?,? FROM updated
      """,
        space,
        departing.toString(),
        space,
        Timestamp.from(now),
        space,
        Timestamp.from(now),
        space,
        actor,
        Timestamp.from(now));
  }

  public void history(
      UUID space,
      UUID item,
      long version,
      String operation,
      UUID document,
      String status,
      UUID member,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        space,
        item,
        version,
        operation,
        document,
        status,
        member,
        Timestamp.from(now));
  }

  public record Item(
      UUID id,
      String type,
      long version,
      UUID document,
      String status,
      String body,
      boolean available) {
    @Override
    public String toString() {
      return "CareItem[id=" + id + "]";
    }
  }
}
