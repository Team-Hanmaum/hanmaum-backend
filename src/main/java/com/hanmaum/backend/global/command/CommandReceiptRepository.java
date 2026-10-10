package com.hanmaum.backend.global.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CommandReceiptRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CommandReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** No lease/TTL: commit, rollback or connection loss releases this transaction lock. */
  public boolean tryLock(UUID actorId, UUID requestId) {
    // Hash collisions can cause a temporary retry, never an incorrect receipt match.
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
            Boolean.class,
            "hanmaum:command:" + actorId + ":" + requestId));
  }

  public Optional<CommandReceipt> find(UUID actorId, UUID requestId) {
    return jdbc
        .query(
            """
        SELECT command_kind, scope_key, request_digest, result_refs::text
        FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?
        """,
            (rs, row) ->
                new CommandReceipt(
                    rs.getString("command_kind"),
                    rs.getString("scope_key"),
                    rs.getString("request_digest"),
                    mapper.readValue(
                        rs.getString("result_refs"), new TypeReference<Map<String, String>>() {})),
            actorId,
            requestId)
        .stream()
        .findFirst();
  }

  public void saveSuccess(UUID actorId, UUID requestId, CommandReceipt receipt, Instant startedAt) {
    jdbc.update(
        """
        INSERT INTO command_receipt
          (id, actor_user_id, client_request_id, command_kind, scope_key, request_digest,
           status, result_refs, created_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED', CAST(? AS jsonb), ?, ?)
        """,
        UUID.randomUUID(),
        actorId,
        requestId,
        receipt.commandKind(),
        receipt.scopeKey(),
        receipt.requestDigest(),
        mapper.writeValueAsString(receipt.resultRefs()),
        Timestamp.from(startedAt),
        Timestamp.from(Instant.now()));
  }
}
