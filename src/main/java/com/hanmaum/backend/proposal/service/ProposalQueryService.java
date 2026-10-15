package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.proposal.dto.ProposalAction;
import com.hanmaum.backend.proposal.dto.ProposalDetail;
import com.hanmaum.backend.proposal.dto.ProposalListResponse;
import com.hanmaum.backend.proposal.dto.ProposalStatus;
import com.hanmaum.backend.proposal.dto.ProposalSummary;
import com.hanmaum.backend.proposal.dto.ProposalValues;
import com.hanmaum.backend.proposal.repository.ProposalQueryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProposalQueryService {
  private final ProposalQueryRepository proposals;
  private final ProposalCursor cursors;
  private final ProposalValueMapper values;

  public ProposalQueryService(
      ProposalQueryRepository proposals, ProposalCursor cursors, ProposalValueMapper values) {
    this.proposals = proposals;
    this.cursors = cursors;
    this.values = values;
  }

  public ProposalListResponse list(
      UUID userId, UUID spaceId, String recordId, String status, String cursor, int size) {
    if (size < 1 || size > 50) throw invalid("size", "조회 개수는 1 이상 50 이하로 입력해주세요.");
    UUID record = recordId(recordId);
    ProposalStatus filter = status(status);
    var position = cursors.decode(spaceId, record, filter, cursor);
    var result =
        authorized(
            proposals.find(
                userId,
                spaceId,
                record,
                filter,
                position == null ? null : position.createdAt(),
                position == null ? null : position.proposalId(),
                null,
                size + 1));
    boolean hasNext = result.proposals().size() > size;
    var page = result.proposals().stream().limit(size).toList();
    String next =
        hasNext
            ? cursors.encode(
                spaceId, record, filter, page.getLast().createdAt(), page.getLast().id())
            : null;
    var items =
        page.stream()
            .map(
                p -> {
                  var projected = project(p);
                  return new ProposalSummary(
                      p.id(),
                      p.recordId(),
                      p.analysisId(),
                      p.itemType(),
                      ProposalAction.CREATE_ITEM,
                      p.status(),
                      projected == null ? null : projected.title(),
                      null,
                      null,
                      p.createdAt());
                })
            .toList();
    return new ProposalListResponse(items, next, hasNext);
  }

  public ProposalDetail detail(UUID userId, UUID spaceId, UUID proposalId) {
    var result = authorized(proposals.find(userId, spaceId, null, null, null, null, proposalId, 1));
    if (result.proposals().isEmpty()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    var p = result.proposals().getFirst();
    var projected = project(p);
    boolean redacted = p.status() == ProposalStatus.INVALIDATED;
    return new ProposalDetail(
        p.id(),
        spaceId,
        p.recordId(),
        p.analysisId(),
        p.itemType(),
        ProposalAction.CREATE_ITEM,
        p.status(),
        projected == null ? null : projected.title(),
        null,
        null,
        null,
        null,
        projected,
        List.of(),
        redacted
            ? List.of()
            : List.of(
                new ProposalDetail.Evidence(
                    p.evidenceId(), p.recordId(), p.quote(), true, p.author(), p.recordedAt())),
        null,
        null,
        null,
        redacted ? List.of("title", "proposedValues", "evidence") : List.of(),
        p.createdAt());
  }

  private ProposalValues project(ProposalQueryRepository.View p) {
    return p.status() == ProposalStatus.INVALIDATED
        ? null
        : values.map(p.itemType(), p.document(), p.assigneeId());
  }

  private ProposalQueryRepository.Result authorized(
      Optional<ProposalQueryRepository.Result> query) {
    var result = query.orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!result.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    if (!result.recordFound()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    return result;
  }

  private UUID recordId(String value) {
    if (value == null) return null;
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException exception) {
      throw invalid("recordId", "원본 ID는 UUID로 입력해주세요.");
    }
  }

  private ProposalStatus status(String value) {
    if (value == null) return ProposalStatus.REVIEW_PENDING;
    try {
      return ProposalStatus.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("status", "REVIEW_PENDING 또는 INVALIDATED로 입력해주세요.");
    }
  }

  private ApiException invalid(String field, String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError(field, reason)));
  }
}
