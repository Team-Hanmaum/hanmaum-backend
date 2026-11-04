package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.proposal.repository.ProposalProvenanceRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Caller authorizes the item and holds the same read snapshot throughout the projection. */
@Service
@Transactional(readOnly = true, propagation = Propagation.MANDATORY)
public class ProposalProvenanceService {
  private final ProposalProvenanceRepository repository;

  public ProposalProvenanceService(ProposalProvenanceRepository repository) {
    this.repository = repository;
  }

  public Set<UUID> unavailableEvidence(UUID space, Collection<UUID> evidenceIds) {
    return repository.unavailableEvidence(space, evidenceIds);
  }

  public List<UUID> appliedEvidence(UUID space, UUID item, long version) {
    return repository.appliedEvidence(space, item, version);
  }
}
