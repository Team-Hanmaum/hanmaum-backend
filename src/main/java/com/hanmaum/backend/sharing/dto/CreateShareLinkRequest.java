package com.hanmaum.backend.sharing.dto;

import com.hanmaum.backend.careitem.service.CareItemInput;
import com.hanmaum.backend.careitem.service.CareMutationInput;
import java.util.*;
import tools.jackson.databind.JsonNode;

public record CreateShareLinkRequest(UUID clientRequestId, List<Target> targets) {
  /** Parses the exact issuance intent without coercing versions or booleans. */
  public static CreateShareLinkRequest parse(JsonNode body) {
    CareMutationInput.object(body, "body", Set.of("clientRequestId", "targets"));
    UUID key = CareMutationInput.uuid(body.path("clientRequestId"), "clientRequestId", true);
    var entries = body.path("targets");
    if (!entries.isArray() || entries.isEmpty())
      throw CareItemInput.invalid("targets", "공유할 항목을 하나 이상 선택해주세요.");
    var targets = new ArrayList<Target>();
    var ids = new HashSet<UUID>();
    for (int i = 0; i < entries.size(); i++) {
      var entry = entries.get(i);
      String path = "targets[" + i + "]";
      CareMutationInput.object(
          entry, path, Set.of("itemId", "expectedItemVersion", "includesChange"));
      UUID id = CareMutationInput.uuid(entry.path("itemId"), path + ".itemId", true);
      if (!ids.add(id)) throw CareItemInput.invalid(path + ".itemId", "중복 선택할 수 없습니다.");
      long version =
          CareMutationInput.version(
              entry.path("expectedItemVersion"), path + ".expectedItemVersion", true);
      if (!entry.path("includesChange").isBoolean())
        throw CareItemInput.invalid(path + ".includesChange", "불리언을 입력해주세요.");
      targets.add(new Target(id, version, entry.path("includesChange").asBoolean()));
    }
    return new CreateShareLinkRequest(key, List.copyOf(targets));
  }

  public record Target(UUID itemId, long expectedItemVersion, boolean includesChange) {}
}
