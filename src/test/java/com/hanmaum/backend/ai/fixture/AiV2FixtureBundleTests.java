package com.hanmaum.backend.ai.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the imported bundle, not the unimplemented v2 HTTP, receipt or persistence behavior. */
class AiV2FixtureBundleTests {
  // AI 97972f9ff9980646646947571a8bddfce2df5d9e. Update only with a reviewed shared revision.
  private static final String MANIFEST_SHA256 =
      "524f95bb6c1b27c3ef5cd781a15fd95f372b52b0b79787bf952316b12033183e";
  private static final Map<String, String> SUITES =
      Map.of(
          "STATIC",
          "cases/static.json",
          "RECEIPT",
          "cases/receipts.json",
          "BACKEND",
          "cases/backend.json",
          "LIMITS",
          "cases/limits.json",
          "HTTP",
          "cases/http.json");
  private static final Map<String, Integer> COUNTS =
      Map.of("STATIC", 146, "RECEIPT", 30, "BACKEND", 25, "LIMITS", 102, "HTTP", 25);
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  @Test
  void verifiesPinnedManifestAndEveryImportedFileByte() throws Exception {
    assertThat(sha256(raw("manifest.json"))).isEqualTo(MANIFEST_SHA256);
    var manifest = metadata("manifest.json");
    assertThat(manifest.path("formatVersion").asInt()).isEqualTo(1);
    assertThat(manifest.path("contractVersion").asText()).isEqualTo("v2");
    assertThat(manifest.path("revision").asText()).isEqualTo("v2-20261021.1");
    assertThat(manifest.path("sourceRepository").asText()).isEqualTo("Team-Hanmaum/hanmaum-ai");
    assertThat(manifest.path("files").size()).isEqualTo(183);

    var expected = new HashSet<String>();
    expected.add("manifest.json");
    for (var entry : manifest.path("files")) {
      String relative = entry.path("path").asText();
      assertThat(expected.add(relative)).as("unique inventory: %s", relative).isTrue();
      assertThat(sha256(raw(relative))).as(relative).isEqualTo(entry.path("sha256").asText());
    }
    try (var files = Files.walk(root())) {
      var actual =
          files
              .filter(Files::isRegularFile)
              .map(file -> root().relativize(file).toString().replace('\\', '/'))
              .toList();
      assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
    for (String relative : expected) {
      String text =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw(relative))).toString();
      assertThat(text)
          .as("LF and UTF-8 without BOM: %s", relative)
          .doesNotContain("\r")
          .doesNotStartWith("\uFEFF");
    }
  }

  @Test
  void verifiesCaseInventoryAndMetadataWithoutExecutingScenarios() throws Exception {
    var manifest = metadata("manifest.json");
    assertThat(manifest.path("suites").propertyNames())
        .containsExactlyInAnyOrderElementsOf(SUITES.keySet());
    var ids = new HashSet<String>();
    for (var suite : SUITES.entrySet()) {
      assertThat(manifest.path("suites").path(suite.getKey()).asText()).isEqualTo(suite.getValue());
      var cases = metadata(suite.getValue());
      assertThat(cases.isArray()).isTrue();
      assertThat(cases.size()).isEqualTo(COUNTS.get(suite.getKey()));
      for (var scenario : cases) {
        String id = scenario.path("caseId").asText();
        assertThat(id).isNotBlank();
        assertThat(ids.add(id)).as("unique caseId: %s", id).isTrue();
        assertThat(scenario.path("category").asText()).as(id).isEqualTo(suite.getKey());
        assertThat(scenario.path("contractVersion").asText()).as(id).isEqualTo("v2");
        assertThat(scenario.path("purpose").asText()).as(id).isNotBlank();
        assertThat(scenario.path("expected").isObject()).as(id).isTrue();
        assertThat(scenario.path("contractSections").isArray()).as(id).isTrue();
        assertThat(scenario.path("contractSections").size()).as(id).isPositive();
        for (var section : scenario.path("contractSections")) {
          assertThat(section.isIntegralNumber()).as(id).isTrue();
          assertThat(section.asInt()).as(id).isBetween(1, 16);
        }
      }
    }
    assertThat(strings(manifest.path("caseIds")))
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(ids);
    assertThat(ids).hasSize(328);
  }

  @Test
  void verifiesAllPayloadReferencesIncludingNestedReceiptSteps() throws Exception {
    var referenced = new HashSet<String>();
    for (var scenario : allCases()) {
      String id = scenario.path("caseId").asText();
      visitObjects(
          scenario,
          node -> {
            for (String key : node.propertyNames()) {
              if (key.endsWith("File")) {
                assertThat(node.path(key).isString()).as("%s: %s", id, key).isTrue();
                String relative = node.path(key).asText();
                assertThat(Files.isRegularFile(resolve(relative)))
                    .as("%s: %s", id, relative)
                    .isTrue();
                referenced.add(relative);
              }
            }
            // publicQuery is BE GET metadata, separate from each internal AI request.
            if (node.has("request")) {
              var request = node.path("request");
              assertThat(request.path("method").asText()).as(id).isEqualTo("POST");
              assertThat(request.path("path").asText()).as(id).isEqualTo("/v2/analyses");
              assertThat(request.at("/headers/Content-Type").asText())
                  .as(id)
                  .isEqualTo("application/json");
              assertThat(request.has("bodyFile") || request.has("bodyRecipe")).as(id).isTrue();
            }
          });
    }
    // Generated recipes can introduce a base payload not referenced by a literal bodyFile.
    visitObjects(
        metadata("generated.json"),
        node -> {
          if (node.has("baseFile")) {
            String relative = node.path("baseFile").asText();
            assertThat(Files.isRegularFile(resolve(relative))).as(relative).isTrue();
            referenced.add(relative);
          }
        });
    var payloads = new HashSet<String>();
    for (var entry : metadata("manifest.json").path("files")) {
      String relative = entry.path("path").asText();
      if (relative.startsWith("payloads/")) payloads.add(relative);
    }
    assertThat(referenced).containsExactlyInAnyOrderElementsOf(payloads);
  }

  @Test
  void verifiesDeclaredErrorMappingConsistencyWithoutUsingProductionErrorCodes() throws Exception {
    var catalog = metadata("error-mapping.json");
    assertThat(catalog.path("contractVersion").asText()).isEqualTo("v2");
    var mappings = new HashMap<String, String>();
    for (var entry : catalog.path("mappings")) {
      String key = entry.path("httpStatus").asInt() + ":" + entry.path("aiCode").asText();
      assertThat(mappings.put(key, entry.path("beFailureCode").asText())).as(key).isNull();
    }
    assertThat(mappings).hasSize(13);
    for (var scenario : allCases()) {
      String id = scenario.path("caseId").asText();
      visitObjects(
          scenario,
          node -> {
            if (!node.has("ai") || !node.has("be") || !node.path("ai").has("code")) return;
            var ai = node.path("ai");
            var be = node.path("be");
            String key = ai.path("httpStatus").asInt() + ":" + ai.path("code").asText();
            assertThat(mappings).as(id).containsKey(key);
            assertThat(be.path("failureCode").asText()).as(id).isEqualTo(mappings.get(key));
            assertThat(be.path("state").asText()).as(id).isEqualTo("FAILED");
            assertThat(be.path("storedProposalCount").asInt(-1)).as(id).isZero();
          });
    }
  }

  @Test
  void preservesDuplicateKeysPrecisionAndEscapingAsRawRequestBytes() throws Exception {
    for (String relative :
        List.of("invalid-duplicate-root-key.json", "invalid-duplicate-nested-key.json")) {
      byte[] wire = raw("payloads/requests/" + relative);
      assertThatThrownBy(() -> JSON.readTree(wire))
          .as(relative)
          .isInstanceOf(JacksonException.class);
    }
    assertThat(
            new String(
                raw("payloads/requests/invalid-fraction-seven.json"), StandardCharsets.UTF_8))
        .contains(".1234567");
    assertThat(
            new String(
                raw("payloads/requests/invalid-fraction-seven-zero.json"), StandardCharsets.UTF_8))
        .contains(".1234560");
    byte[] base = raw("payloads/requests/base.json");
    byte[] escaped = raw("payloads/requests/compare-unicode-escape.json");
    assertThat(escaped).isNotEqualTo(base);
    assertThat(JSON.readTree(escaped)).isEqualTo(JSON.readTree(base));
  }

  private static List<JsonNode> allCases() throws Exception {
    var cases = new ArrayList<JsonNode>();
    for (String suite : SUITES.values()) metadata(suite).forEach(cases::add);
    return cases;
  }

  private static void visitObjects(JsonNode node, Consumer<JsonNode> consumer) {
    if (node.isObject()) consumer.accept(node);
    if (node.isObject() || node.isArray()) node.forEach(child -> visitObjects(child, consumer));
  }

  private static List<String> strings(JsonNode array) {
    var result = new ArrayList<String>();
    array.forEach(value -> result.add(value.asText()));
    return result;
  }

  private static Path root() {
    try {
      return Path.of(
              Objects.requireNonNull(
                      AiV2FixtureBundleTests.class.getResource("/ai-contract/v2/manifest.json"))
                  .toURI())
          .getParent();
    } catch (Exception exception) {
      throw new IllegalStateException("Missing shared v2 test resources", exception);
    }
  }

  private static Path resolve(String relative) {
    Path path = root().resolve(relative).normalize();
    if (relative.isBlank()
        || Path.of(relative).isAbsolute()
        || relative.contains("\\")
        || relative.contains(":")
        || !path.startsWith(root())
        || Files.isSymbolicLink(path)) {
      throw new IllegalArgumentException("Invalid fixture resource path: " + relative);
    }
    return path;
  }

  // Preserve the wire bytes. Do not deserialize and reserialize negative HTTP request examples.
  private static byte[] raw(String relative) throws Exception {
    return Files.readAllBytes(resolve(relative));
  }

  private static JsonNode metadata(String relative) throws Exception {
    return JSON.readTree(raw(relative));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
