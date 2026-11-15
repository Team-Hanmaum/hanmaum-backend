package com.hanmaum.backend.proposal.dto;

/** Proposal lifecycle; member change requests have their own status. */
public enum ProposalStatus {
  REVIEW_PENDING,
  APPLIED,
  DECLINED,
  INVALIDATED
}
