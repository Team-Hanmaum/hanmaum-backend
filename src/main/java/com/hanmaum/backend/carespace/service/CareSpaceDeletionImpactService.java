package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.repository.CareSpaceDeletionRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CareSpaceDeletionImpactService {
  private final CareSpaceQueryService spaces;
  private final CareSpaceDeletionRepository repository;
  private final CommandDigest digests;

  public CareSpaceDeletionImpactService(
      CareSpaceQueryService spaces, CareSpaceDeletionRepository repository, CommandDigest digests) {
    this.spaces = spaces;
    this.repository = repository;
    this.digests = digests;
  }

  /** Counts and comparison graph share one authorized read snapshot without changing any row. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CareSpaceDeletionImpact preview(UUID actor, UUID space) {
    var current = spaces.detail(actor, space);
    if (current.role() != CareSpaceRole.OWNER) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return snapshot(actor, space, Long.parseLong(current.version()));
  }

  /** Used after the deletion transaction acquires the same space lock as every domain mutation. */
  CareSpaceDeletionImpact snapshot(UUID actor, UUID space, long version) {
    var parts =
        new ArrayList<>(
            List.of(
                "care-space-deletion-impact:v1",
                actor.toString(),
                space.toString(),
                Long.toString(version)));
    var counts = new HashMap<String, Long>();
    for (var table : repository.snapshot(space)) {
      counts.put(table.table(), (long) table.states().size());
      parts.add(table.table());
      parts.add(Long.toString(table.states().size()));
      parts.addAll(table.states());
    }
    return new CareSpaceDeletionImpact(
        space,
        Long.toString(version),
        new CareSpaceDeletionImpact.Counts(
            counts.get("space_membership"),
            counts.get("source_record"),
            counts.get("care_item"),
            counts.get("ai_proposal"),
            counts.get("change_request"),
            counts.get("care_item_history")),
        digests.digest(parts.toArray(String[]::new)));
  }
}
