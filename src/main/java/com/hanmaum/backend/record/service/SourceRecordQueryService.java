package com.hanmaum.backend.record.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.record.dto.SourceRecordDetail;
import com.hanmaum.backend.record.dto.SourceRecordListResponse;
import com.hanmaum.backend.record.dto.SourceRecordSummary;
import com.hanmaum.backend.record.repository.SourceRecordQueryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SourceRecordQueryService {
  private final SourceRecordQueryRepository records;
  private final SourceRecordCursor cursors;

  public SourceRecordQueryService(SourceRecordQueryRepository records, SourceRecordCursor cursors) {
    this.records = records;
    this.cursors = cursors;
  }

  public SourceRecordListResponse list(UUID userId, UUID spaceId, String cursor, int size) {
    if (size < 1 || size > 50)
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("size", "조회 개수는 1 이상 50 이하로 입력해주세요.")));
    var position = cursors.decode(spaceId, cursor);
    var result =
        requireAuthorized(
            records.list(
                userId,
                spaceId,
                position == null ? null : position.recordedAt(),
                position == null ? null : position.recordId(),
                size + 1));
    boolean hasNext = result.records().size() > size;
    var page = result.records().stream().limit(size).toList();
    String next =
        hasNext ? cursors.encode(spaceId, page.getLast().recordedAt(), page.getLast().id()) : null;
    var items =
        page.stream()
            .map(
                r ->
                    new SourceRecordSummary(
                        r.id(), r.content(), r.author(), r.recordedAt(), r.isMine(), r.analysis()))
            .toList();
    return new SourceRecordListResponse(items, next, hasNext);
  }

  public SourceRecordDetail detail(UUID userId, UUID spaceId, UUID recordId) {
    var result = requireAuthorized(records.detail(userId, spaceId, recordId));
    if (result.records().isEmpty()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    var r = result.records().getFirst();
    return new SourceRecordDetail(
        r.id(),
        spaceId,
        r.content(),
        r.author(),
        r.recordedAt(),
        Long.toString(r.version()),
        r.isMine(),
        r.analysis(),
        r.proposalIds(),
        result.owner() || r.isMine());
  }

  private SourceRecordQueryRepository.Result requireAuthorized(
      Optional<SourceRecordQueryRepository.Result> query) {
    var result = query.orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!result.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return result;
  }
}
