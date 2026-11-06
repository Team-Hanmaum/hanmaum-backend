package com.hanmaum.backend.record.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact.AffectedItem;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact.Counts;
import com.hanmaum.backend.record.repository.SourceRecordDeletionImpactRepository;
import com.hanmaum.backend.record.repository.SourceRecordDeletionImpactRepository.ImpactRow;
import com.hanmaum.backend.record.repository.SourceRecordDeletionImpactRepository.Kind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRecordDeletionImpactService {
  private final SourceRecordDeletionImpactRepository repository;
  private final CommandDigest digest;

  public SourceRecordDeletionImpactService(
      SourceRecordDeletionImpactRepository repository, CommandDigest digest) {
    this.repository = repository;
    this.digest = digest;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SourceRecordDeletionImpact preview(UUID actor, UUID space, UUID record) {
    return snapshot(actor, space, record, requireVersion(actor, space, record, false)).impact();
  }

  long requireVersion(UUID actor, UUID space, UUID record, boolean includeErased) {
    var access =
        repository
            .access(actor, space, record, includeErased)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!access.participating()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    if (access.version() == null) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    if (!access.canDelete()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return access.version();
  }

  Snapshot snapshot(UUID actor, UUID space, UUID record, long recordVersion) {
    String version = Long.toString(recordVersion);
    var rows = repository.snapshot(space, record);
    var counts = new EnumMap<Kind, Long>(Kind.class);
    var items = new ArrayList<AffectedItem>();
    var tokenParts =
        new ArrayList<>(
            List.of(
                "source-record-deletion-impact:v1",
                actor.toString(),
                space.toString(),
                record.toString(),
                version));
    for (var row : rows) {
      counts.merge(row.kind(), 1L, Long::sum);
      if (row.kind() == Kind.ITEM) {
        items.add(
            new AffectedItem(
                row.id(), Long.toString(row.version()), row.independent(), row.reconfirm()));
      }
      tokenParts.add(row.kind().name());
      tokenParts.add(row.id().toString());
      tokenParts.add(Long.toString(row.version()));
      tokenParts.add(Boolean.toString(row.independent()));
      tokenParts.add(Boolean.toString(row.reconfirm()));
      tokenParts.add(row.state());
    }
    var impact =
        new SourceRecordDeletionImpact(
            record,
            version,
            new Counts(
                counts.getOrDefault(Kind.PROPOSAL, 0L),
                counts.getOrDefault(Kind.EVIDENCE, 0L),
                counts.getOrDefault(Kind.ITEM, 0L),
                counts.getOrDefault(Kind.HISTORY, 0L)),
            List.copyOf(items),
            digest.digest(tokenParts.toArray(String[]::new)));
    return new Snapshot(impact, rows);
  }

  record Snapshot(SourceRecordDeletionImpact impact, List<ImpactRow> rows) {}
}
