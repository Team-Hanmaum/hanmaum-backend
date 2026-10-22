package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.proposal.repository.ChangeRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Owner-specific current-state proof. Never replaces current authorization or optimistic checks.
 */
@Component
public class ChangeRequestReviewToken {
  private final ChangeRequestRepository requests;
  private final ProposalReviewToken proposals;
  private final CommandDigest digests;

  public ChangeRequestReviewToken(
      ChangeRequestRepository requests, ProposalReviewToken proposals, CommandDigest digests) {
    this.requests = requests;
    this.proposals = proposals;
    this.digests = digests;
  }

  public String issue(UUID actor, UUID space, ChangeRequestRepository.Entry e) {
    if (!"PENDING".equals(e.status()) || !e.memberActive() || e.values() == null) return null;
    UUID owner = requests.currentOwner(actor, space);
    if (owner == null || !requests.reviewable(space, e.id())) return null;
    String proposal = proposals.issue(actor, space, e.proposalId());
    if (proposal == null) return null;
    return digests.digest(
        "CHANGE_REQUEST_REVIEW",
        actor.toString(),
        space.toString(),
        owner.toString(),
        e.id().toString(),
        e.requestId().toString(),
        e.memberId().toString(),
        e.documentId().toString(),
        e.action(),
        e.values(),
        String.valueOf(e.targetItemId()),
        String.valueOf(e.expectedItemVersion()),
        proposal);
  }
}
