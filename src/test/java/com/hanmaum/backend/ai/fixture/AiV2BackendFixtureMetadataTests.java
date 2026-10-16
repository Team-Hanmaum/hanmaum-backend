package com.hanmaum.backend.ai.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Consumes scenario metadata only; does not execute v2 HTTP calls or database mutations. */
class AiV2BackendFixtureMetadataTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void verifiesPublicQueryTargetsViewersAndDeletionExpectations() throws Exception {
    for (var scenario : read("cases/backend.json")) {
      String id = scenario.path("caseId").asText();
      var request = read(scenario.at("/request/bodyFile").asText());
      var query = scenario.path("publicQuery");
      var expected = scenario.at("/expected/be");
      assertThat(query.path("method").asText()).as(id).isEqualTo("GET");
      assertThat(query.path("pathTemplate").asText())
          .as(id)
          .isEqualTo("/api/spaces/{spaceId}/source-records/{recordId}/analyses/{analysisId}");
      assertThat(uuid(query.path("spaceId"))).as(id).isEqualTo(uuid(request.path("careSpaceId")));
      assertThat(uuid(query.path("recordId"))).as(id).isEqualTo(uuid(request.path("recordId")));
      assertThat(query.path("analysisIdFrom").asText()).as(id).isEqualTo("CURRENT_RUN");
      assertThat(query.path("phase").asText()).as(id).isEqualTo("AFTER_SCENARIO");
      assertThat(query.path("viewer").asText())
          .as(id)
          .isEqualTo(
              hasAction(scenario, "END_REQUESTER_MEMBERSHIP")
                  ? "OTHER_ACTIVE_SPACE_MEMBER"
                  : "ACTIVE_SPACE_MEMBER");

      boolean sourceDeleted = hasAction(scenario, "DELETE_SOURCE_RECORD");
      assertThat(expected.path("queryHttpStatus").asInt())
          .as(id)
          .isEqualTo(sourceDeleted ? 404 : 200);
      if (sourceDeleted) {
        assertThat(expected.path("queryErrorCode").asText()).as(id).isEqualTo("RESOURCE_NOT_FOUND");
        for (var event : scenario.path("events")) {
          if (event.path("action").asText().equals("DELETE_SOURCE_RECORD")) {
            assertThat(uuid(event.path("recordId"))).as(id).isEqualTo(uuid(query.path("recordId")));
          }
        }
      } else {
        assertThat(expected.has("queryErrorCode")).as(id).isFalse();
      }

      // A public 404 is independent of internal state and content erasure assertions.
      if (id.equals("C-source-deleted")) {
        assertThat(expected.path("state").asText()).isEqualTo("DISCARDED");
        assertThat(expected.path("storedProposalCount").asInt(-1)).isZero();
      } else if (id.equals("C-delete-derived-copies")) {
        assertThat(expected.path("completionState").asText()).isEqualTo("COMPLETED");
        assertThat(expected.path("storedAtCompletion").asInt()).isEqualTo(4);
        assertThat(expected.has("state")).isFalse();
        assertThat(expected.path("deletedSourceTextVisible")).isEqualTo(JSON.valueToTree(false));
        assertThat(expected.path("deletedSourceTextStoredInDerivedCopies"))
            .isEqualTo(JSON.valueToTree(false));
      }
    }
  }

  @Test
  void verifiesDetachEventsRemoveInputLinksAndPreserveTheirContent() throws Exception {
    int detached = 0;
    for (var scenario : read("cases/backend.json")) {
      String id = scenario.path("caseId").asText();
      var request = read(scenario.at("/request/bodyFile").asText());
      for (var event : scenario.path("events")) {
        assertThat(event.has("keepBasisRow") || event.has("keepDocument")).as(id).isFalse();
        if (!event.path("action").asText().equals("DETACH_BASIS")) continue;
        detached++;
        var candidate = findById(request.path("candidates"), "itemId", uuid(event.path("itemId")));
        var basis = findById(candidate.path("basis"), "basisId", uuid(event.path("basisId")));
        assertThat(event.path("basisKind").asText()).as(id).isEqualTo(basis.path("kind").asText());
        assertFlag(event, "removeCareItemBasisRow", true, id);
        assertFlag(event, "preserveBasisContent", true, id);
      }
    }
    assertThat(detached).isEqualTo(7);
  }

  @Test
  void verifiesDirectInputSetupAndAllEightInvalidationCombinations() throws Exception {
    var combinations = new HashSet<String>();
    var ids = new HashSet<String>();
    for (var scenario : read("cases/backend.json")) {
      if (!scenario.has("setup")) continue;
      String id = scenario.path("caseId").asText();
      ids.add(id);
      var setup = scenario.at("/setup/directInputDocuments");
      assertThat(setup.isArray()).as(id).isTrue();
      assertThat(setup.size()).as(id).isEqualTo(1);
      var documents = setup.get(0);
      var request = read(scenario.at("/request/bodyFile").asText());
      UUID itemId = uuid(documents.path("itemId"));
      UUID basisId = uuid(documents.path("basisId"));
      var candidate = findById(request.path("candidates"), "itemId", itemId);
      var basis = findById(candidate.path("basis"), "basisId", basisId);
      assertThat(basis.path("kind").asText()).as(id).isEqualTo("DIRECT_INPUT");
      assertThat(basis.path("text").asText()).as(id).isNotBlank();
      assertThat(uuid(documents.path("currentDocumentId")))
          .as(id)
          .isNotEqualTo(uuid(documents.path("basisDocumentId")));

      assertThat(scenario.path("events").size()).as(id).isEqualTo(1);
      var event = scenario.path("events").get(0);
      assertThat(uuid(event.path("itemId"))).as(id).isEqualTo(itemId);
      assertThat(uuid(event.path("basisId"))).as(id).isEqualTo(basisId);
      assertThat(event.path("basisKind").asText()).as(id).isEqualTo("DIRECT_INPUT");
      String action = event.path("action").asText();
      String phase = event.path("phase").asText();
      assertThat(action).as(id).isIn("DETACH_BASIS", "INVALIDATE_DIRECT_DOCUMENT");
      assertThat(phase).as(id).isIn("BEFORE_COMPLETION", "AFTER_COMPLETION");
      boolean detached = action.equals("DETACH_BASIS");
      if (!detached) {
        assertThat(uuid(event.path("documentId")))
            .as(id)
            .isEqualTo(uuid(documents.path("basisDocumentId")));
        assertFlag(event, "redactBody", true, id);
        assertFlag(event, "retainCareItemBasisRow", true, id);
      }

      var response = read(scenario.at("/aiResponse/bodyFile").asText());
      boolean referenced = false;
      boolean targeted = false;
      for (var suggestion : response.path("suggestions")) {
        if (!suggestion.path("targetItemId").isNull()) {
          targeted |= uuid(suggestion.path("targetItemId")).equals(itemId);
        }
        for (var reference : suggestion.path("basisReferences")) {
          if (uuid(reference.path("itemId")).equals(itemId)) {
            for (var referencedId : reference.path("basisIds")) {
              referenced |= uuid(referencedId).equals(basisId);
            }
            // An unreferenced scenario must invalidate a different input candidate entirely.
            assertThat(id.startsWith("C-unreferenced-")).as(id).isFalse();
          }
        }
      }
      assertThat(referenced).as(id).isEqualTo(!id.startsWith("C-unreferenced-"));
      assertThat(targeted).as(id).isEqualTo(referenced);
      assertThat(combinations.add(phase + ":" + action + ":" + referenced)).as(id).isTrue();

      var expected = scenario.at("/expected/be");
      assertFlag(expected, "currentDocumentUnchanged", true, id);
      assertFlag(expected, "candidateVersionUnchanged", true, id);
      var after = expected.path("basisAfterEvent");
      assertFlag(after, "careItemBasisRowExists", !detached, id);
      assertFlag(after, "documentExists", true, id);
      assertFlag(after, "documentValid", detached, id);
      if (phase.equals("BEFORE_COMPLETION")) {
        assertThat(expected.path("state").asText()).as(id).isEqualTo("DISCARDED");
        assertThat(expected.path("storedProposalCount").asInt(-1)).as(id).isZero();
      } else {
        assertThat(expected.path("completionState").asText()).as(id).isEqualTo("COMPLETED");
        assertThat(expected.path("storedAtCompletion").asInt()).as(id).isEqualTo(1);
        assertThat(expected.path("visibleValidProposalCount").asInt(-1)).as(id).isZero();
        assertThat(expected.path("visibleValidBasisCount").asInt(-1)).as(id).isZero();
        assertThat(expected.has("state")).as(id).isFalse();
      }
    }
    assertThat(ids)
        .containsExactlyInAnyOrder(
            "C-direct-basis-detached", "C-direct-document-invalidated",
            "C-direct-basis-detached-query", "C-direct-document-invalidated-query",
            "C-unreferenced-direct-basis", "C-unreferenced-direct-document",
            "C-unreferenced-direct-basis-query", "C-unreferenced-direct-document-query");
    assertThat(combinations).hasSize(8);
  }

  private static void assertFlag(JsonNode node, String field, boolean value, String caseId) {
    assertThat(node.path(field)).as("%s: %s", caseId, field).isEqualTo(JSON.valueToTree(value));
  }

  private static boolean hasAction(JsonNode scenario, String action) {
    for (var event : scenario.path("events")) {
      if (event.path("action").asText().equals(action)) return true;
    }
    return false;
  }

  private static JsonNode findById(JsonNode array, String field, UUID id) {
    List<JsonNode> matches = new ArrayList<>();
    for (var node : array) {
      if (uuid(node.path(field)).equals(id)) matches.add(node);
    }
    assertThat(matches).as("unique %s: %s", field, id).hasSize(1);
    return matches.getFirst();
  }

  private static UUID uuid(JsonNode node) {
    return UUID.fromString(node.asText());
  }

  private static JsonNode read(String relative) throws Exception {
    try (var input =
        Objects.requireNonNull(
            AiV2BackendFixtureMetadataTests.class.getResourceAsStream(
                "/ai-contract/v2/" + relative))) {
      return JSON.readTree(input.readAllBytes());
    }
  }
}
