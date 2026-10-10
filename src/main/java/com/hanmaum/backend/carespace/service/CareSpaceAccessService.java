package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.dto.CareSpaceAccess;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.repository.CareSpaceAccessRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Called by a business service inside the same write transaction as its mutation. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CareSpaceAccessService {
  private final CareSpaceAccessRepository access;

  public CareSpaceAccessService(CareSpaceAccessRepository access) {
    this.access = access;
  }

  public CareSpaceAccess requireActiveMember(UUID userId, UUID spaceId) {
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Care space access locks require a write transaction");
    }
    return access.lockCurrentAccess(userId, spaceId);
  }

  public CareSpaceAccess requireOwner(UUID userId, UUID spaceId) {
    var result = requireActiveMember(userId, spaceId);
    if (result.role() != CareSpaceRole.OWNER) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return result;
  }
}
