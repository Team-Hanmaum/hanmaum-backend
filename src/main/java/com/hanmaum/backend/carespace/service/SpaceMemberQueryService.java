package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.dto.SpaceMemberListResponse;
import com.hanmaum.backend.carespace.repository.SpaceMemberQueryRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SpaceMemberQueryService {
  private final SpaceMemberQueryRepository members;

  public SpaceMemberQueryService(SpaceMemberQueryRepository members) {
    this.members = members;
  }

  public SpaceMemberListResponse list(UUID userId, UUID spaceId) {
    var result =
        members
            .find(userId, spaceId)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!result.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return new SpaceMemberListResponse(result.members());
  }
}
