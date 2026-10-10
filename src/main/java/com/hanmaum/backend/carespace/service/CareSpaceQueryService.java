package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.dto.CareSpaceDetail;
import com.hanmaum.backend.carespace.dto.CareSpaceListResponse;
import com.hanmaum.backend.carespace.repository.CareSpaceQueryRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CareSpaceQueryService {
  private final CareSpaceQueryRepository queries;

  public CareSpaceQueryService(CareSpaceQueryRepository queries) {
    this.queries = queries;
  }

  public CareSpaceListResponse list(UUID userId) {
    return new CareSpaceListResponse(queries.findCurrentSpaces(userId));
  }

  public CareSpaceDetail detail(UUID userId, UUID spaceId) {
    var result =
        queries
            .findDetail(spaceId, userId)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (result.membershipId() == null) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return result;
  }
}
