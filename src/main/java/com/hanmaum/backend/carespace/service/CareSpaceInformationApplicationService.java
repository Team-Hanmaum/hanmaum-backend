package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.careitem.service.CareMutationInput;
import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.repository.CareSpaceInformationRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CareSpaceInformationApplicationService {
  private final CareSpaceInformationRepository spaces;
  private final ObjectMapper mapper;

  public CareSpaceInformationApplicationService(
      CareSpaceInformationRepository spaces, ObjectMapper mapper) {
    this.spaces = spaces;
    this.mapper = mapper;
  }

  public String label(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode())
      return SubjectLabel.normalize(null);
    if (!value.isTextual()) throw new ApiException(CommonErrorCode.INVALID_REQUEST);
    return SubjectLabel.normalize(value.asText());
  }

  public Prepared prepare(UUID space, long expected, JsonNode values) {
    CareMutationInput.object(values, "values", Set.of("subjectLabel"));
    String label = label(values.path("subjectLabel"));
    var current = spaces.current(space);
    if (current.version() != expected) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    boolean changed = !current.label().equals(label);
    if (changed && current.version() == Long.MAX_VALUE)
      throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    if (spaces.duplicate(space, current.owner(), label))
      throw new ApiException(CareSpaceErrorCode.SPACE_LABEL_DUPLICATED);
    return new Prepared(label, current.version(), current.updatedAt(), changed);
  }

  public Applied apply(UUID space, Prepared plan, Instant now) {
    if (plan.changed()) {
      try {
        spaces.update(space, plan.version(), plan.label(), now);
      } catch (DuplicateKeyException ex) {
        throw new ApiException(CareSpaceErrorCode.SPACE_LABEL_DUPLICATED);
      }
    }
    // Keep text in a redactable document, never in command receipt metadata.
    UUID document =
        spaces.document(
            space,
            mapper.writeValueAsString(mapper.createObjectNode().put("subjectLabel", plan.label())),
            now);
    return new Applied(
        document,
        plan.label(),
        plan.changed() ? plan.version() + 1 : plan.version(),
        plan.changed() ? now : plan.updatedAt());
  }

  public record Prepared(String label, long version, Instant updatedAt, boolean changed) {
    @Override
    public String toString() {
      return "SpaceInformation[version=" + version + "]";
    }
  }

  public record Applied(UUID document, String label, long version, Instant updatedAt) {}
}
