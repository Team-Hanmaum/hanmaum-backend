package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.analysis.dto.AnalysisDetail;
import com.hanmaum.backend.analysis.repository.AnalysisQueryRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisQueryService {
  private final AnalysisQueryRepository queries;

  public AnalysisQueryService(AnalysisQueryRepository queries) {
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public AnalysisDetail find(UUID user, UUID space, UUID record, UUID analysis) {
    var result =
        queries
            .find(user, space, record, analysis)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!result.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    if (result.detail() == null) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    return result.detail();
  }
}
