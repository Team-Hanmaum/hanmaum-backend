package com.hanmaum.backend.proposal.dto;

/** Approval requests are a separate, not-yet-implemented workflow. */
public enum ProposalStatus {
  REVIEW_PENDING,
  APPLIED,
  DECLINED,
  INVALIDATED
}
